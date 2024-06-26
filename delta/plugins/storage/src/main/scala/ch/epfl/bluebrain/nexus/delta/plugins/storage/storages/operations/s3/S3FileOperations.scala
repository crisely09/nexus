package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{BodyPartEntity, ContentType, Uri}
import akka.util.ByteString
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedFetchError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations.S3FileMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

import scala.concurrent.duration.DurationInt

trait S3FileOperations {
  def checkBucketExists(bucket: String): IO[Unit]

  def fetch(bucket: String, path: Uri.Path): IO[AkkaSource]

  def save(
      storage: S3Storage,
      filename: String,
      entity: BodyPartEntity,
      contentLength: Long
  ): IO[FileStorageMetadata]

  def register(bucket: String, path: Uri.Path): IO[S3FileMetadata]
}

object S3FileOperations {
  final case class S3FileMetadata(contentType: Option[ContentType], metadata: FileStorageMetadata)

  private val log       = Logger[S3FileOperations]
  private val ChunkSize = 8 * 1024

  def mk(client: S3StorageClient, locationGenerator: S3LocationGenerator)(implicit
      as: ActorSystem,
      uuidf: UUIDF
  ): S3FileOperations = new S3FileOperations {

    private lazy val saveFile = new S3StorageSaveFile(client, locationGenerator)

    override def checkBucketExists(bucket: String): IO[Unit] = {
      client.bucketExists(bucket).flatMap { exists =>
        IO.raiseUnless(exists)(StorageNotAccessible(s"Bucket $bucket does not exist"))
      }
    }

    override def fetch(bucket: String, path: Uri.Path): IO[AkkaSource] =
      IO.delay {
        StreamConverter(
          client
            .readFile(bucket, UrlUtils.decode(path))
            .groupWithin(ChunkSize, 1.second)
            .map(bytes => ByteString(bytes.toArray))
            .adaptError {
              case _: NoSuchKeyException => FetchFileRejection.FileNotFound(path.toString)
              case err                   => UnexpectedFetchError(path.toString, err.getMessage)
            }
        )
      }

    override def save(
        storage: S3Storage,
        filename: String,
        entity: BodyPartEntity,
        contentLength: Long
    ): IO[FileStorageMetadata] = {
      saveFile.save(storage, filename, entity, contentLength)
    }

    override def register(bucket: String, path: Uri.Path): IO[S3FileMetadata] =
      registerInternal(client, bucket, path)

  }

  def registerInternal(client: S3StorageClient, bucket: String, path: Uri.Path)(implicit
      uuidF: UUIDF
  ): IO[S3FileMetadata] = {
    for {
      _        <- log.debug(s"Fetching attributes for S3 file. Bucket $bucket at path $path")
      resp     <- client.headObject(bucket, UrlUtils.decode(path))
      metadata <- mkS3Metadata(path, resp)
    } yield metadata
  }
    .onError { e =>
      log.error(e)(s"Failed fetching required attributes for S3 file registration. Bucket $bucket and path $path")
    }

  private def mkS3Metadata(path: Uri.Path, resp: HeadObject)(implicit
      uuidf: UUIDF
  ) =
    for {
      uuid <- uuidf()
    } yield S3FileMetadata(
      resp.contentType,
      FileStorageMetadata(
        uuid,
        resp.fileSize,
        resp.digest,
        FileAttributesOrigin.External,
        Uri(path.toString()),
        path
      )
    )

}
