package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchGlobalEventLog.IndexingData
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{obj, predicate}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema, schemas, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceResolution.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventExchangeCollection
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceResolutionGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResolverResolutionRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{Resource, ResourceEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ResourcesDummy._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Projects, ResourceResolution, Resources}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{DiscardedMessage, ProjectionId, SuccessMessage}
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import fs2.Chunk
import io.circe.Json
import monix.bio.IO
import monix.execution.Scheduler

import scala.concurrent.duration._
import java.time.Instant
import java.util.UUID

class ElasticSearchGlobalEventLogSpec extends AbstractDBSpec with ConfigFixtures with EitherValuable {

  val am       = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  val projBase = nxv.base

  val org         = Label.unsafe("myorg")
  val org2        = Label.unsafe("myorg2")
  val project     = ProjectGen.project("myorg", "myproject", base = projBase, mappings = am)
  val project2    = ProjectGen.project("myorg2", "myproject2", base = projBase, mappings = am)
  val project3    = ProjectGen.project("myorg", "myproject3", base = projBase, mappings = am)
  val projectRef  = project.ref
  val project2Ref = project2.ref
  val project3Ref = project3.ref

  implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  val uuid                                = UUID.randomUUID()
  implicit val uuidF: UUIDF               = UUIDF.fixed(uuid)
  implicit val projectionId: ProjectionId = ViewProjectionId("elasticsearch-projection")

  val epoch: Instant            = Instant.EPOCH
  implicit val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit val caller: Caller   = Caller(subject, Set(subject))

  implicit val scheduler: Scheduler = Scheduler.global

  private val neverFetch: (ResourceRef, ProjectRef) => FetchResource[Schema] = { case (ref, pRef) =>
    IO.raiseError(ResolverResolutionRejection.ResourceNotFound(ref.iri, pRef))
  }
  implicit def res: RemoteContextResolution                                  =
    RemoteContextResolution.fixed(
      contexts.metadata -> jsonContentOf("contexts/metadata.json"),
      contexts.shacl    -> jsonContentOf("contexts/shacl.json")
    )

  val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    res,
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  lazy val projectSetup = ProjectSetup
    .init(
      orgsToCreate = org :: org2 :: Nil,
      projectsToCreate = project :: project2 :: Nil
    )
    .accepted

  val resourceResolution: ResourceResolution[Schema] = ResourceResolutionGen.singleInProject(projectRef, neverFetch)

  val journal: ResourcesJournal =
    Journal[ResourceIdentifier, ResourceEvent](
      Resources.moduleType,
      1L,
      (ev: ResourceEvent) =>
        Set("event", Projects.projectTag(ev.project), Organizations.orgTag(ev.project.organization))
    ).accepted

  val orgs     = projectSetup._1
  val projects = projectSetup._2

  val resources = {
    for {
      r <- ResourcesDummy(orgs, projects, resourceResolution, resolverContextResolution, journal)
    } yield r
  }.accepted

  val exchange = Resources.eventExchange(resources)

  val globalEventLog = ElasticSearchGlobalEventLog(
    journal.asInstanceOf[EventLog[Envelope[Event]]],
    projects,
    orgs,
    new EventExchangeCollection(Set(exchange)),
    2,
    50.millis
  )
  val resourceSchema = Latest(schemas.resources)
  val tpe            = nxv + "Tpe"
  val name1          = "myName"
  val name2          = "myName2"

  val myId          = nxv + "myid" // Resource created against the resource schema with id present on the payload
  val myId2         = nxv + "myid" // Resource created against the resource schema with id present on the payload
  val source        = jsonContentOf("indexing/resource.json", "id" -> myId, "type" -> tpe, "number" -> 10, "name" -> name1)
  val sourceUpdated = source deepMerge Json.obj("number" -> Json.fromInt(42))
  val source2       = jsonContentOf("indexing/resource.json", "id" -> myId2, "type" -> tpe, "number" -> 20, "name" -> name2)

  val r1Created = resources.create(myId, projectRef, schemas.resources, source).accepted
  val r1Updated = resources.update(myId, projectRef, None, 1L, sourceUpdated).accepted
  val r2Created = resources.create(myId2, project2Ref, schemas.resources, source2).accepted

  // TODO: This is wrong. Persistence id is generated differently on Dummies and Implementations (due to Journal)
  def resourceId(id: Iri, project: ProjectRef) = s"${Resources.moduleType}-($project,$id)"

  def toIndexData(res: Resource, name: String) = {
    val graph = Graph.empty(res.id).add(predicate(skos.prefLabel), obj(name))
    IndexingData(graph, res.source)
  }

  val allEvents =
    List(
      Chunk(
        DiscardedMessage(Sequence(1), r1Created.updatedAt, resourceId(r1Updated.id, projectRef), 1),
        SuccessMessage(
          Sequence(2),
          r1Updated.updatedAt,
          resourceId(r1Updated.id, projectRef),
          2,
          r1Updated.map(toIndexData(_, name1)),
          Vector.empty
        )
      ),
      Chunk(
        SuccessMessage(
          Sequence(3),
          r2Created.updatedAt,
          resourceId(r2Created.id, project2Ref),
          1,
          r2Created.map(toIndexData(_, name2)),
          Vector.empty
        )
      )
    )

  "An ElasticSearchGlobalEventLog" should {

    "fetch all events" in {

      val events = globalEventLog
        .stream(NoOffset, None)
        .take(2)
        .compile
        .toList
        .accepted

      events shouldEqual allEvents
    }

    "fetch events for a project" in {
      val events = globalEventLog
        .stream(project2Ref, NoOffset, None)
        .accepted
        .take(1)
        .compile
        .toList
        .accepted

      events shouldEqual allEvents.drop(1)

    }

    "fetch events for an organization" in {
      val events = globalEventLog
        .stream(org, NoOffset, None)
        .accepted
        .take(1)
        .compile
        .toList
        .accepted

      events shouldEqual allEvents.take(1)
    }

    "fail to fetch the events for non-existent project" in {
      globalEventLog
        .stream(project3Ref, NoOffset, None)
        .rejected shouldEqual ProjectNotFound(project3Ref)
    }
  }

}