package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.{Metadata, RebuildStrategy}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, NonEmptySet, TagLabel}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

import java.util.UUID
import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration

/**
  * Representation of a composite view.
  *
  * @param id               the id of the project
  * @param project          the project to which this view belongs
  * @param sources          the collection of sources for the view
  * @param projections      the collection of projections for the view
  * @param rebuildStrategy  the rebuild strategy of the view
  * @param uuid             the uuid of the view
  * @param tags             the tag -> rev mapping
  * @param source           the original json document provided at creation or update
  */
final case class CompositeView(
    id: Iri,
    project: ProjectRef,
    sources: NonEmptySet[CompositeViewSource],
    projections: NonEmptySet[CompositeViewProjection],
    rebuildStrategy: Option[RebuildStrategy],
    uuid: UUID,
    tags: Map[TagLabel, Long],
    source: Json
) {

  /**
    * @return [[CompositeView]] metadata
    */
  def metadata: Metadata = Metadata(uuid)
}

object CompositeView {

  /**
    * The rebuild strategy for a [[CompositeView]].
    */
  sealed trait RebuildStrategy extends Product with Serializable

  /**
    * Rebuild strategy defining rebuilding at a certain interval.
    */
  final case class Interval(value: FiniteDuration) extends RebuildStrategy

  final case class Metadata(uuid: UUID)

  object RebuildStrategy {
    @nowarn("cat=unused")
    implicit final val rebuildStrategyEncoder: Encoder[RebuildStrategy] = {
      implicit val config: Configuration                          = Configuration.default.withDiscriminator(keywords.tpe)
      implicit val finiteDurationEncoder: Encoder[FiniteDuration] = Encoder.encodeString.contramap(_.toString())
      deriveConfiguredEncoder[RebuildStrategy]
    }
  }

  @nowarn("cat=unused")
  implicit private def compositeViewEncoder(implicit base: BaseUri): Encoder.AsObject[CompositeView] = {
    implicit val config: Configuration                     = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val encoderTags: Encoder[Map[TagLabel, Long]] = Encoder.instance(_ => Json.Null)
    Encoder.encodeJsonObject.contramapObject { v =>
      deriveConfiguredEncoder[CompositeView]
        .encodeObject(v)
        .add(keywords.tpe, Set(nxv + "View", compositeViewType).asJson)
        .remove("tags")
        .remove("project")
        .remove("source")
        .remove("id")
    }
  }

  implicit def compositeViewJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[CompositeView] =
    JsonLdEncoder.computeFromCirce(_.id, ContextValue(contexts.compositeViews))

  implicit private val compositeViewMetadataEncoder: Encoder.AsObject[Metadata] =
    Encoder.encodeJsonObject.contramapObject(meta => JsonObject("_uuid" -> meta.uuid.asJson))

  implicit val compositeViewMetadataJsonLdEncoder: JsonLdEncoder[Metadata]      =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.compositeViewsMetadata))
}