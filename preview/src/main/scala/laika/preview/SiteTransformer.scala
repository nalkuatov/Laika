/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laika.preview

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}

import cats.effect.{Async, Resource}
import cats.syntax.all._
import laika.api.Renderer
import laika.api.builder.OperationConfig
import laika.ast.{DocumentTreeRoot, MessageFilter, Path}
import laika.config.Config.ConfigResult
import laika.config.{ConfigException, LaikaKeys}
import laika.factory.{BinaryPostProcessorBuilder, TwoPhaseRenderFormat}
import laika.format.HTML
import laika.io.api.{BinaryTreeRenderer, TreeParser, TreeRenderer}
import laika.io.config.SiteConfig
import laika.io.implicits._
import laika.io.model.{BinaryInput, InputTreeBuilder, ParsedTree, StringTreeOutput}
import laika.rewrite.nav.Selections
import laika.theme.Theme

private [preview] class SiteTransformer[F[_]: Async] (val parser: TreeParser[F], 
                                                      htmlRenderer: TreeRenderer[F],
                                                      binaryRenderers: Seq[(BinaryTreeRenderer[F], String)],
                                                      inputs: InputTreeBuilder[F],
                                                      staticFiles: Map[Path, SiteResult[F]],
                                                      artifactBasename: String) {

  val parse = {
    val allInputs = staticFiles.foldLeft(inputs) {
      case (inputs, (path, _)) => inputs.addProvidedPath(path)
    }
    parser.fromInput(allInputs).parse
  }

  def renderBinary (renderer: BinaryTreeRenderer[F], root: DocumentTreeRoot, staticDocs: Seq[BinaryInput[F]]): Resource[F, InputStream] = {
    val renderResult = for {
      out <- Async[F].delay(new ByteArrayOutputStream(1024 * 64))
      _   <- renderer.from(root).copying(staticDocs).toStream(Async[F].pure(out)).render
    } yield out.toByteArray
    
    Resource
      .eval(renderResult)
      .flatMap(bytes => Resource.eval(Async[F].delay(new ByteArrayInputStream(bytes))))
  }
  
  def transformBinaries (tree: ParsedTree[F]): ConfigResult[Map[Path, SiteResult[F]]] = {
    for {
      roots            <- Selections.createCombinations(tree.root)
      downloadPath     <- SiteConfig.downloadPath(tree.root.config)
      artifactBaseName <- tree.root.config.get[String](LaikaKeys.artifactBaseName, artifactBasename)
    } yield {
      val combinations = for {
        root <- roots.toList
        renderer <- binaryRenderers
      } yield (root, renderer)
      combinations.map { case ((root, classifiers), (renderer, suffix)) =>
        val classifier = if (classifiers.value.isEmpty) "" else "-" + classifiers.value.mkString("-")
        val docName = artifactBaseName + classifier + "." + suffix
        val path = downloadPath / docName
        (path, StaticResult(renderBinary(renderer, root, tree.staticDocuments)))
      }.toMap
    }
  }

  def transformHTML (tree: ParsedTree[F]): F[Map[Path, SiteResult[F]]] = {
    htmlRenderer
      .from(tree.root)
      .copying(tree.staticDocuments)
      .toOutput(StringTreeOutput)
      .render
      .map { root =>
        val map = root.allDocuments.map { doc =>
          (doc.path, RenderedResult[F](doc.content))
        }.toMap ++
        root.staticDocuments.map { doc =>
          (doc.path, StaticResult(doc.input))
        }.toMap
        val roots = map.flatMap { case (path, result) =>
          if (path.name == "index.html") Some((path.parent, result)) else None 
        }
        map ++ roots
      }
  }

  val transform: F[SiteResults[F]] = for { 
    tree     <- parse
    rendered <- transformHTML(tree)
    ebooks   <- Async[F].fromEither(transformBinaries(tree).leftMap(ConfigException.apply))
  } yield {
    new SiteResults(staticFiles ++ rendered ++ ebooks)
  }
  
}

private [preview] object SiteTransformer {

  def htmlRenderer[F[_]: Async] (config: OperationConfig, 
                                 theme: Theme[F]): Resource[F, TreeRenderer[F]] = 
    Renderer
      .of(HTML)
      .withConfig(config)
      .renderMessages(MessageFilter.Info)
      .parallel[F]
      .withTheme(theme)
      .build
  
  def binaryRenderer[F[_]: Async, FMT] (format: TwoPhaseRenderFormat[FMT, BinaryPostProcessorBuilder],
                                        config: OperationConfig,
                                        theme: Theme[F]): Resource[F, BinaryTreeRenderer[F]] = {
    Renderer
      .of(format)
      .withConfig(config)
      .renderMessages(MessageFilter.Info)
      .parallel[F]
      .withTheme(theme)
      .build
  }

  def create[F[_]: Async](parser: Resource[F, TreeParser[F]],
                          inputs: InputTreeBuilder[F],
                          renderFormats: List[TwoPhaseRenderFormat[_, BinaryPostProcessorBuilder]],
                          staticFiles: Option[StaticFileScanner],
                          artifactBasename: String): Resource[F, SiteTransformer[F]] = {
    
    def adjustConfig (p: TreeParser[F]): TreeParser[F] = p.modifyConfig(oc => oc.copy(
      failOnMessages = MessageFilter.None,
      configBuilder = oc.configBuilder
        .withValue(LaikaKeys.preview.enabled, true)
    ))
    
    def collectFiles (config: OperationConfig): Resource[F, Map[Path, SiteResult[F]]] = staticFiles
      .fold(Resource.pure[F, Map[Path, SiteResult[F]]](Map.empty))(st => 
        Resource.eval(st.collectStaticFiles[F](config))
      )
    
    for {
      p      <- parser.map(adjustConfig)
      html   <- htmlRenderer(p.config, p.theme)
      bin    <- renderFormats.map(f => binaryRenderer(f, p.config, p.theme).map((_, f.description.toLowerCase))).sequence
      static <- collectFiles(p.config)
    } yield new SiteTransformer[F](p, html, bin, inputs, static, artifactBasename)
    
  }
  
}

class SiteResults[F[_]: Async] (map: Map[Path, SiteResult[F]]) {
  
  def get (path: Path): Option[SiteResult[F]] = map.get(path)
  
  def list: List[Path] = map.keySet.toList.sortBy(_.toString)
  
}

sealed abstract class SiteResult[F[_]: Async] extends Product with Serializable
case class RenderedResult[F[_]: Async] (content: String) extends SiteResult[F]
case class StaticResult[F[_]: Async] (content: Resource[F, InputStream]) extends SiteResult[F]
