package actors

import akka.actor.{ActorRef, Props, Actor}
import akka.pattern.{ask, pipe}
import akka.routing.SmallestMailboxPool
import akka.util.Timeout
import models.documentation._
import play.api.i18n.Lang
import play.api.libs.iteratee.Enumerator
import utils.PlayGitRepository
import scala.concurrent.duration._

/**
 * Protocol for the documentation actor
 */
object DocumentationActor {

  /**
   * A response
   */
  sealed trait Response[+T]

  /**
   * A request
   */
  sealed trait Request[R <: Response[R]] {
    def version: Version
    def cacheId: Option[String]
  }

  /**
   * A request that has a language
   */
  sealed trait LangRequest[R <: Response[R]] extends Request[R] {
    def lang: Option[Lang]
  }

  /**
   * The resource requested was found
   */
  sealed trait Found[T] extends Response[T] {
    def cacheId: String
  }

  /**
   * The resource requested was not found.
   *
   * @param translationContext The translation context.
   */
  case class NotFound(translationContext: TranslationContext) extends Response[Nothing]

  /**
   * The resource requested was not modified
   */
  case class NotModified(cacheId: String) extends Response[Nothing]

  /**
   * A request to render a page.
   *
   * @param lang The language to render the page for, none for the default language.
   * @param version The version of the documentation to fetch the page for.
   * @param page The page to render.
   */
  case class RenderPage(lang: Option[Lang], version: Version, cacheId: Option[String], page: String) extends LangRequest[RenderedPage]

  /**
   * A rendered page.
   *
   * @param pageHtml The HTML of the page.
   * @param sidebarHtml The HTML of the sidebar, if it exists.
   * @param source The GitHub source of the page.
   * @param translationContext The translation context.
   * @param cacheId The cacheid.
   */
  case class RenderedPage(pageHtml: String, sidebarHtml: Option[String], source: Option[String],
                          translationContext: TranslationContext, cacheId: String) extends Found[RenderedPage]

  /**
   * Load a resource.
   *
   * @param lang The language to load the resource for, none for the default language.
   * @param version The version of the documentation to get the resource from.
   * @param resource The resource path.
   */
  case class LoadResource(lang: Option[Lang], version: Version, cacheId: Option[String], resource: String) extends LangRequest[Resource]

  /**
   * Load an API resource.
   *
   * @param version The version of the documentation to load the resource for.
   * @param resource The resource path.
   */
  case class LoadApi(version: Version, cacheId: Option[String], resource: String) extends Request[Resource]

  /**
   * A resource.
   *
   * @param content The content of the resource.
   * @param size The size of the resource.
   */
  case class Resource(content: Enumerator[Array[Byte]], size: Long, cacheId: String) extends Found[Resource]

  /**
   * Update the documentation.
   *
   * @param documentation The documnetation to update.
   */
  case class UpdateDocumentation(documentation: Documentation)

  /**
   * Get a summary of the documentation.
   */
  case object GetSummary

  /**
   * Render a V1 page
   *
   * @param lang The language to render the page for, none for the default language.
   * @param version The version of the documentation to fetch the page for.
   * @param page The page to render.
   */
  case class RenderV1Page(lang: Option[Lang], version: Version, cacheId: Option[String], page: String) extends LangRequest[RenderedPage]

  case class RenderV1Cheatsheet(lang: Option[Lang], version: Version, cacheId: Option[String], category: String) extends LangRequest[V1Cheatsheet]

  case class V1Cheatsheet(sheets: Seq[String], title: String, otherCategories: Map[String, String],
                          context: TranslationContext, cacheId: String) extends Found[V1Cheatsheet]

  /**
   * A summary of the documentation.
   *
   * @param defaultLatest The latest version of the default language.
   * @param defaultLang The default language.
   * @param allLangs All languages.
   * @param translations All translations and their latest versions.
   * @param translationContext The translation context.
   */
  case class DocumentationSummary(defaultLatest: Option[Version], defaultLang: Lang, allLangs: Seq[Lang],
                                  translations: Map[Lang, Option[Version]], translationContext: TranslationContext)

  // Not part of protocol:

  case class DocumentationGitRepos(default: DocumentationGitRepo, generated: PlayGitRepository,
                                   translations: Seq[DocumentationGitRepo])

  case class DocumentationGitRepo(config: TranslationConfig, repo: PlayGitRepository)


  def props(config: DocumentationConfig) = Props(new DocumentationActor(config))
}

class DocumentationActor(config: DocumentationConfig) extends Actor {

  import DocumentationActor._
  import actors.{ DocumentationLoadingActor => Loader }
  import context.dispatcher

  implicit val timeout = Timeout(5.seconds)

  private def createRepo(config: TranslationConfig) = {
    new DocumentationGitRepo(config, new PlayGitRepository(config.repo, config.remote, config.basePath))
  }

  private val repos = DocumentationGitRepos(
    createRepo(config.default),
    new PlayGitRepository(config.generatedRepo.repo, config.generatedRepo.remote, config.generatedRepo.basePath),
    config.translations.map(createRepo)
  )

  private val poller = context.actorOf(
    Props(new DocumentationPollingActor(repos, self))
      .withDispatcher("polling-dispatcher"),
    "documentationPoller"
  )

  private val loader = context.actorOf(
    Props[DocumentationLoadingActor]
      .withRouter(SmallestMailboxPool(nrOfInstances = 4))
      .withDispatcher("loader-dispatcher"),
    "documentationLoaders")

  override def postStop() = {
    repos.default.repo.close()
    repos.generated.close()
    repos.translations.foreach(_.repo.close())
  }

  val version21 = Version("2.1", 2, 1, 0, 0, Release)

  var documentation: Documentation = null

  var pending: List[(Any, ActorRef)] = Nil

  /**
   * Initial state, documentation not loaded, all messages buffered.
   */
  def receive = {
    case UpdateDocumentation(docs) =>
      documentation = docs
      context.become(documentationLoaded)
      pending.reverse.foreach(m => self.tell(m._1, m._2))
      pending = Nil
    case other =>
      pending = (other, sender()) :: pending
  }

  /**
   * Final state, documentation loaded
   */
  def documentationLoaded: Actor.Receive = {

    case UpdateDocumentation(docs) =>
      documentation = docs

    case rp @ RenderPage(_, version, _, page) =>
      loaderRequest[play.doc.RenderedPage](rp) { tv =>
        Loader.RenderPage(page, tv.playDoc)
      } { (lang, translation, tv, page) =>

        val source = if (version.versionType.isLatest) {
          translation.source.map { gitHubSource =>
            gitHubSource.format(tv.symbolicName, page.path)
          }
        } else None

        RenderedPage(page.html, page.sidebarHtml, source, translationContext(lang, version, translation), tv.cacheId)
      }

    case rp @ RenderV1Page(_, version, _, page) =>
      loaderRequest[String](rp) { tv =>
        Loader.RenderV1Page(page, tv.repo)
      } { (lang, translation, tv, content) =>
        RenderedPage(content, None, None, translationContext(lang, version, translation), tv.cacheId)
      }

    case lr: LoadResource =>
      loaderRequest[Loader.Resource](lr) { tv =>
        Loader.LoadResource(lr.resource, tv.repo)
      } { (_, _, tv, resource) =>
        Resource(resource.content, resource.size, tv.cacheId)
      }

    case LoadApi(version, cacheId, resource) =>
      documentation.default.byVersion.get(version) match {
        case Some(tr) if cacheId.exists(_ == tr.cacheId) =>
          sender() ! NotModified(tr.cacheId)
        case Some(tr) =>
          val resourceRequest = for {
            maybeResource <- (loader ? Loader.LoadResource("api/" + resource, tr.repo)).mapTo[Option[Loader.Resource]]
          } yield {
            maybeResource.map { resource =>
              Resource(resource.content, resource.size, tr.cacheId)
            } getOrElse NotFound(notFoundTranslationContext())
          }

          resourceRequest pipeTo sender()

        case None => sender() ! NotFound(notFoundTranslationContext())
      }

    case GetSummary =>
      sender() ! DocumentationSummary(documentation.default.defaultVersion, documentation.defaultLang, documentation.allLangs,
        documentation.translations.mapValues(_.defaultVersion), notFoundTranslationContext())

    case rc @ RenderV1Cheatsheet(_, version, _, category) =>
      loaderRequest[Loader.V1Cheatsheet](rc) { tv =>
        Loader.RenderV1Cheatsheet(category, tv.repo)
      } { (lang, translation, tv, cs) =>
        V1Cheatsheet(cs.sheets, cs.title, cs.otherCategories, translationContext(lang, version, translation), tv.cacheId)
      }
  }

  /**
   * Do the given block for the given lang
   */
  private def forLang[T](req: LangRequest[_])(block: (Lang, Translation, TranslationVersion) => Unit) = {
    // Fold lang
    req.lang.fold[Option[(Lang, Translation)]](
      // No lang, use default
      Some((documentation.defaultLang, documentation.default))
    ) { lang =>
      // Lang was requested, see if we have that lang
      documentation.translations.get(lang).map { translation =>
        (lang, translation)
      }
    } match {
      case Some((lang, translation)) =>

        translation.byVersion.get(req.version) match {
          case Some(tv) =>
            req.cacheId match {
              case Some(cacheId) if cacheId == tv.cacheId => sender() ! NotModified(cacheId)
              case _ => block(lang, translation, tv)
            }
          case _ => sender() ! NotFound(notFoundTranslationContext(lang, translation.displayVersions))
        }

      // No lang was found
      case _ => sender() ! NotFound(notFoundTranslationContext())
    }
  }

  /**
   * Make a loader request for the given language and version.
   *
   * The generated message, when asked from the loader, is expected to return an option of the given return type.
   *
   * @param incomingRequest The request message
   * @param loaderRequest A function that creates the request message from the given repository
   * @param response A function that creates the response message from the answer, to send back to the sender
   */
  private def loaderRequest[T](incomingRequest: LangRequest[_])(loaderRequest: TranslationVersion => Any)
                              (response: (Lang, Translation, TranslationVersion, T) => Response[_]) = {
    forLang(incomingRequest) { (lang, translation, tv) =>
      val askRequest = for {
        answerOpt <- (loader ? loaderRequest(tv)).mapTo[Option[T]]
      } yield {
        answerOpt.map { answer =>
          response(lang, translation, tv, answer)
        } getOrElse NotFound(notFoundTranslationContext(lang, translation.displayVersions))
      }

      askRequest pipeTo sender()
    }
  }

  /**
   * Get the translation context for the given language and version.
   */
  private def translationContext(lang: Lang, version: Version, translation: Translation): TranslationContext = {
    val isDefault = lang == documentation.defaultLang
    val defaultAlternative = AlternateTranslation(documentation.defaultLang, true,
      findMostSuitableMatch(version, documentation.default.availableVersions.map(_.version)))
    val alternatives = documentation.translations.toList.sortBy(_._1.code).map {
      case (l, t) =>
        AlternateTranslation(l, false, findMostSuitableMatch(version, t.availableVersions.map(_.version)))
    }
    TranslationContext(lang, isDefault, Some(version), translation.displayVersions, defaultAlternative :: alternatives)
  }

  /**
   * Find the most suitable match for the given version from the list of candidates.
   *
   * The most suitable match is defined as a version that is of the same major version, and is either an identical
   * version, or if no identical version is found, is the most recent stable version of the candidates, otherwise
   * the most recent version of the candidates.  If there are no candidates of the same major version, none is returned.
   */
  private def findMostSuitableMatch(version: Version, candidates: List[Version]): Option[Version] = {
    val sameMajors = candidates.filter(_.sameMajor(version))
    sameMajors.find(_ == version).orElse {
      sameMajors.find(v => v.versionType.isLatest || v.versionType.isStable)
    }.orElse(sameMajors.headOption)
  }

  /**
   * The translation context for if a resource is not found.
   */
  def notFoundTranslationContext(lang: Lang = documentation.defaultLang,
                                 displayVersions: List[Version] = documentation.default.displayVersions) = {
    TranslationContext(lang, lang == documentation.defaultLang, None, displayVersions,
      AlternateTranslation(documentation.defaultLang, true, None) ::
        documentation.translations.toList.sortBy(_._1.code).map {
          case (l, translation) =>
            AlternateTranslation(l, false, None)
        }
    )
  }
}
