package laika.render.epub

import laika.ast.{DocumentTreeRoot, Path, TemplateDocument}
import laika.directive.StandardDirectives
import laika.io.model.{BinaryInput, RenderedTreeRoot}
import laika.parse.directive.TemplateParsers
import laika.parse.markup.DocumentParser.ParserInput
import laika.parse.text.TextParsers.unsafeParserFunction

/** Processes CSS inputs for EPUB containers.
  *
  * @author Jens Halm
  */
object StyleSupport {

  /** Path for the fallback styles that will be inserted
    * when the user has not added any CSS documents to the input tree.
    */
  val fallbackStylePath: Path = Path.Root / "styles" / "fallback.css"

  /** Collects all CSS inputs (recursively) in the provided document tree.
    * CSS inputs are recognized by file suffix).
    */
  def collectStyles (root: RenderedTreeRoot): Seq[BinaryInput] = root.staticDocuments.filter(_.path.suffix == "css")

  /** Verifies that the specified document tree contains at least one CSS file
    * (determined by file suffix). If this is the case the tree is returned unchanged,
    * otherwise a new tree with a minimal fallback CSS inserted into the root is returned instead.
    */
  def ensureContainsStyles (root: DocumentTreeRoot): DocumentTreeRoot = {
    if (root.staticDocuments.exists(_.suffix == "css")) root
    else root.copy(staticDocuments = root.staticDocuments :+ fallbackStylePath)
  }

  /** Parser for the EPUB-XHTML default template that supports the `styleLinks` directive.
    */
  object XHTMLTemplateParser extends TemplateParsers(
      Map(StandardDirectives.styleLinksDirective.name -> StandardDirectives.styleLinksDirective)) {
    def parse (input: ParserInput): TemplateDocument = {
      val root = unsafeParserFunction(templateRoot)(input.context)
      TemplateDocument(input.path, root)
    }
  }

}
