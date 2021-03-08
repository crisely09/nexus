package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchGlobalEventLog.IndexingData
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingCoordinator.ElasticSearchIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.{ElasticSearchGlobalEventLog, ElasticSearchIndexingCoordinator}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewEvent, contexts, schema => viewsSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.{EventExchange, EventExchangeCollection, GlobalEventLog}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CacheProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Message, Projection, ProjectionId, ProjectionProgress}
import ch.epfl.bluebrain.nexus.migration.ElasticSearchViewsMigration
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * ElasticSearch plugin wiring.
  */
class ElasticSearchPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader = getClass.getClassLoader

  make[ElasticSearchViewsConfig].from { ElasticSearchViewsConfig.load(_) }

  make[EventLog[Envelope[ElasticSearchViewEvent]]].fromEffect { databaseEventLog[ElasticSearchViewEvent](_, _) }

  make[HttpClient].named("elasticsearch-client").from {
    (cfg: ElasticSearchViewsConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
      HttpClient()(cfg.client, as.classicSystem, sc)
  }

  make[ElasticSearchClient].from {
    (cfg: ElasticSearchViewsConfig, client: HttpClient @Id("elasticsearch-client"), as: ActorSystem[Nothing]) =>
      new ElasticSearchClient(client, cfg.base)(as.classicSystem)
  }

  make[GlobalEventLog[Message[ResourceF[IndexingData]]]].from {
    (
        cfg: ElasticSearchViewsConfig,
        eventLog: EventLog[Envelope[Event]],
        projects: Projects,
        orgs: Organizations,
        eventExchanges: EventExchangeCollection
    ) =>
      implicit val projectionId: ProjectionId = CacheProjectionId("ElasticSearchGlobalEventLog")
      ElasticSearchGlobalEventLog(
        eventLog,
        projects,
        orgs,
        eventExchanges,
        cfg.indexing.maxBatchSize,
        cfg.indexing.maxTimeWindow
      )
  }

  make[ProgressesCache].named("elasticsearch-progresses").from {
    (cfg: ElasticSearchViewsConfig, as: ActorSystem[Nothing]) =>
      KeyValueStore.distributed[ProjectionId, ProjectionProgress[Unit]](
        "elasticsearch-views-progresses",
        (_, v) => v.timestamp.toEpochMilli
      )(as, cfg.keyValueStore)
  }

  make[ElasticSearchIndexingCoordinator].fromEffect {
    (
        eventLog: GlobalEventLog[Message[ResourceF[IndexingData]]],
        client: ElasticSearchClient,
        projection: Projection[Unit],
        cache: ProgressesCache @Id("elasticsearch-progresses"),
        config: ElasticSearchViewsConfig,
        as: ActorSystem[Nothing],
        scheduler: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        base: BaseUri
    ) =>
      ElasticSearchIndexingCoordinator(eventLog, client, projection, cache, config)(as, scheduler, cr, base)
  }

  make[ElasticSearchViews]
    .fromEffect {
      (
          cfg: ElasticSearchViewsConfig,
          log: EventLog[Envelope[ElasticSearchViewEvent]],
          client: ElasticSearchClient,
          permissions: Permissions,
          projects: Projects,
          coordinator: ElasticSearchIndexingCoordinator,
          clock: Clock[UIO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          scheduler: Scheduler,
          cr: RemoteContextResolution @Id("aggregate")
      ) =>
        ElasticSearchViews(cfg, log, projects, permissions, client, coordinator)(uuidF, clock, scheduler, as, cr)
    }

  make[ElasticSearchViewsQuery].from {
    (
        acls: Acls,
        projects: Projects,
        views: ElasticSearchViews,
        client: ElasticSearchClient,
        cfg: ElasticSearchViewsConfig
    ) =>
      ElasticSearchViewsQuery(acls, projects, views, client)(cfg.indexing)
  }

  make[ProgressesStatistics].named("elasticsearch-statistics").from {
    (cache: ProgressesCache @Id("elasticsearch-progresses"), projectsCounts: ProjectsCounts) =>
      new ProgressesStatistics(cache, projectsCounts)
  }

  make[ElasticSearchViewsRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        projects: Projects,
        views: ElasticSearchViews,
        viewsQuery: ElasticSearchViewsQuery,
        progresses: ProgressesStatistics @Id("elasticsearch-statistics"),
        coordinator: ElasticSearchIndexingCoordinator,
        baseUri: BaseUri,
        cfg: ElasticSearchViewsConfig,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        resourcesToSchemaSet: Set[ResourceToSchemaMappings]
    ) =>
      val resourceToSchema = resourcesToSchemaSet.foldLeft(ResourceToSchemaMappings.empty)(_ + _)
      new ElasticSearchViewsRoutes(
        identities,
        acls,
        projects,
        views,
        viewsQuery,
        progresses,
        coordinator,
        resourceToSchema
      )(
        baseUri,
        cfg.pagination,
        cfg.indexing,
        s,
        cr,
        ordering
      )
  }

  make[ElasticSearchScopeInitialization]

  make[ElasticSearchViewsMigration].from { (elasticSearchViews: ElasticSearchViews) =>
    new ElasticSearchViewsMigrationImpl(elasticSearchViews)
  }

  many[ScopeInitialization].ref[ElasticSearchScopeInitialization]

  many[EventExchange].add { (views: ElasticSearchViews) => views.eventExchange }

  many[RemoteContextResolution].addEffect {
    for {
      elasticsearchCtx    <- ioJsonContentOf("contexts/elasticsearch.json")
      elasticsearchIdxCtx <- ioJsonContentOf("contexts/elasticsearch-indexing.json")
    } yield RemoteContextResolution.fixed(
      contexts.elasticsearch         -> elasticsearchCtx,
      contexts.elasticsearchIndexing -> elasticsearchIdxCtx
    )
  }

  many[ResourceToSchemaMappings].add(
    ResourceToSchemaMappings(Label.unsafe("views") -> viewsSchemaId.iri)
  )

  many[ApiMappings].add(ElasticSearchViews.mappings)

  many[PriorityRoute].add { (route: ElasticSearchViewsRoutes) => PriorityRoute(priority, route.routes) }

  many[ServiceDependency].add { new ElasticSearchServiceDependency(_) }
}