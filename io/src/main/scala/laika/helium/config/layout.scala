/*
 * Copyright 2012-2020 the original author or authors.
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

package laika.helium.config

import laika.ast.Path.Root
import laika.ast._
import laika.parse.{ GeneratedSource, SourceFragment }

private[helium] sealed trait CommonLayout {
  def defaultBlockSpacing: Length
  def defaultLineHeight: Double
}

private[helium] case class WebLayout(
    contentWidth: Length,
    navigationWidth: Length,
    topBarHeight: Length,
    defaultBlockSpacing: Length,
    defaultLineHeight: Double,
    anchorPlacement: AnchorPlacement
) extends CommonLayout

private[helium] case class WebContent(
    favIcons: Seq[Favicon] = Nil,
    htmlIncludes: HTMLIncludes = HTMLIncludes(),
    topNavigationBar: TopNavigationBar = TopNavigationBar.default,
    mainNavigation: MainNavigation = MainNavigation(),
    pageNavigation: PageNavigation = PageNavigation(),
    footer: Option[TemplateSpan] = Some(HeliumFooter.default),
    landingPage: Option[LandingPage] = None,
    tableOfContent: Option[TableOfContent] = None,
    downloadPage: Option[DownloadPage] = None
)

private[helium] case class PDFLayout(
    pageWidth: Length,
    pageHeight: Length,
    marginTop: Length,
    marginRight: Length,
    marginBottom: Length,
    marginLeft: Length,
    defaultBlockSpacing: Length,
    defaultLineHeight: Double,
    keepTogetherDecoratedLines: Int,
    tableOfContent: Option[TableOfContent] = None
) extends CommonLayout

private[helium] case class EPUBLayout(
    defaultBlockSpacing: Length,
    defaultLineHeight: Double,
    keepTogetherDecoratedLines: Int,
    tableOfContent: Option[TableOfContent] = None
) extends CommonLayout

private[helium] case class TableOfContent(title: String, depth: Int)

private[helium] case class TopNavigationBar(
    homeLink: ThemeLink,
    navLinks: Seq[ThemeLink],
    versionMenu: VersionMenu = VersionMenu.default,
    highContrast: Boolean = false
)

private[helium] object TopNavigationBar {

  def withHomeLink(path: Path): TopNavigationBar =
    TopNavigationBar(IconLink.internal(path, HeliumIcon.home), Nil)

  val default: TopNavigationBar = TopNavigationBar(DynamicHomeLink.default, Nil)
}

object HeliumFooter {

  val default: TemplateSpanSequence = TemplateSpanSequence.adapt(
    TemplateString("Site generated by "),
    SpanLink.external("https://typelevel.org/Laika/")("Laika"),
    TemplateString(" with the Helium theme.")
  )

}

private[helium] case class MainNavigation(
    depth: Int = 2,
    includePageSections: Boolean = false,
    prependLinks: Seq[ThemeNavigationSection] = Nil,
    appendLinks: Seq[ThemeNavigationSection] = Nil
)

private[helium] case class PageNavigation(
    enabled: Boolean = true,
    depth: Int = 2,
    sourceBaseURL: Option[String] = None,
    sourceLinkText: String = "Source for this page",
    keepOnSmallScreens: Boolean = false
)

private[helium] case class DownloadPage(
    title: String,
    description: Option[String],
    downloadPath: Path = Root / "downloads",
    includeEPUB: Boolean = true,
    includePDF: Boolean = true
)

private[helium] case class LandingPage(
    logo: Option[Image] = None,
    title: Option[String] = None,
    subtitle: Option[String] = None,
    latestReleases: Seq[ReleaseInfo] = Nil,
    license: Option[String] = None,
    titleLinks: Seq[ThemeLink] = Nil,
    documentationLinks: Seq[TextLink] = Nil,
    projectLinks: Seq[ThemeLinkSpan] = Nil,
    teasers: Seq[Teaser] = Nil,
    styles: Seq[Path] = Nil
) {

  import LengthUnit._

  val subtitleFontSize: Length =
    if (subtitle.exists(_.length > 75)) px(22)
    else if (subtitle.exists(_.length > 55)) px(27)
    else px(32)

  val teaserTitleFontSize: Length = if (teasers.size <= 4) px(28) else px(20)
  val teaserBodyFontSize: Length  = if (teasers.size <= 4) px(17) else px(15)

}

/** In contrast to the public `LinkGroup` this UI component allows all types of links as children, including menus.
  */
private[helium] case class GenericLinkGroup(links: Seq[ThemeLink], options: Options = NoOpt)
    extends BlockResolver {
  type Self = GenericLinkGroup
  val source: SourceFragment = GeneratedSource

  def resolve(cursor: DocumentCursor): Block = {
    val resolvedLinks = links.map {
      case sr: SpanResolver  => SpanSequence(sr.resolve(cursor))
      case br: BlockResolver => br.resolve(cursor)
    }
    BlockSequence(resolvedLinks, HeliumStyles.linkRow + options)
  }

  def withOptions(newOptions: Options): GenericLinkGroup =
    new GenericLinkGroup(links, newOptions) {}

  def unresolvedMessage: String            = s"Unresolved link group: $this"
  def runsIn(phase: RewritePhase): Boolean = phase.isInstanceOf[RewritePhase.Render]
}

/** Configuration for a single favicon which can be an internal resource or an external URL.
  *
  * The sizes string will be used in the corresponding `sizes` attribute of the generated `&lt;link&gt;` tag.
  */
case class Favicon private (
    target: Target,
    sizes: Option[String],
    mediaType: Option[String]
)

/** Companion for creating Favicon configuration instances.
  */
object Favicon {

  private def mediaType(suffix: Option[String]): Option[String] = suffix.collect {
    case "ico"          => "image/x-icon"
    case "png"          => "image/png"
    case "gif"          => "image/gif"
    case "jpg" | "jpeg" => "image/jpeg"
    case "svg"          => "image/svg+xml"
  }

  /** Creates the configuration for a single favicon with an external URL.
    *
    * The sizes string will be used in the corresponding `sizes` attribute of the generated `&lt;link&gt;` tag.
    */
  def external(url: String, sizes: String, mediaType: String): Favicon =
    Favicon(ExternalTarget(url), Some(sizes), Some(mediaType))

  /** Creates the configuration for a single favicon with an external URL.
    */
  def external(url: String, mediaType: String): Favicon =
    Favicon(ExternalTarget(url), None, Some(mediaType))

  /** Creates the configuration for a single favicon based on an internal resource and its virtual path.
    * This resource must be part of the inputs known to Laika.
    *
    * The sizes string will be used in the corresponding `sizes` attribute of the generated `&lt;link&gt;` tag.
    */
  def internal(path: Path, sizes: String): Favicon =
    Favicon(InternalTarget(path), Some(sizes), mediaType(path.suffix))

  /** Creates the configuration for a single favicon based on an internal resource and its virtual path.
    * This resource must be part of the inputs known to Laika.
    */
  def internal(path: Path): Favicon =
    Favicon(InternalTarget(path), None, mediaType(path.suffix))

}

/** Represents release info to be displayed on the landing page.
  *
  * This is specific for sites that serve as documentation for software projects.
  *
  * @param title the header above the version number, e.g. "Latest Stable Release"
  * @param version the version number of the release
  */
case class ReleaseInfo(title: String, version: String)

/** Represents a single teaser block to be displayed on the landing page.
  * Any number of these blocks can be passed to the Helium configuration.
  */
case class Teaser(title: String, description: String)

/** Configures the anchor placement for section headers.
  * Anchors appear on mouse-over and allow to copy the direct link to the section.
  */
sealed trait AnchorPlacement

object AnchorPlacement {

  /** Disables anchors for all section headers. */
  object None extends AnchorPlacement

  /** Places anchors to the left of section headers. */
  object Left extends AnchorPlacement

  /** Places anchors to the right of section headers. */
  object Right extends AnchorPlacement
}

private[helium] case class HTMLIncludes(
    includeCSS: Seq[Path] = Seq(Root),
    includeJS: Seq[Path] = Seq(Root)
)

private[helium] object HeliumStyles {
  val row: Options           = Styles("row")
  val linkRow: Options       = Styles("row", "links")
  val buttonLink: Options    = Styles("button-link")
  val textLink: Options      = Styles("text-link")
  val iconLink: Options      = Styles("icon-link")
  val imageLink: Options     = Styles("image-link")
  val menuToggle: Options    = Styles("menu-toggle")
  val menuContainer: Options = Styles("menu-container")
  val menuContent: Options   = Styles("menu-content")
  val versionMenu: Options   = Styles("version-menu")
}

private[helium] case class ThemeFonts(body: String, headlines: String, code: String)

private[helium] case class FontSizes(
    body: Length,
    code: Length,
    title: Length,
    header2: Length,
    header3: Length,
    header4: Length,
    small: Length
)
