package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model

import akka.http.scaladsl.model.{ContentType, Uri}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.{Decoder, DecodingFailure}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._

// $COVERAGE-OFF$
/**
  * Holds all the metadata information related to the file.
  *
  * @param location
  *   the file location
  * @param bytes
  *   the size of the file file in bytes
  * @param digest
  *   the digest information of the file
  * @param mediaType
  *   the media type of the file
  */
final case class RemoteDiskStorageFileAttributes(
    location: Uri,
    bytes: Long,
    digest: Digest,
    mediaType: ContentType
)

object RemoteDiskStorageFileAttributes {

  implicit private val config: Configuration =
    Configuration.default
      .copy(transformMemberNames = {
        case "@context" => "@context"
        case key        => s"_$key"
      })

  implicit val fileAttrDecoder: Decoder[RemoteDiskStorageFileAttributes] = {

    implicit val computedDigestDecoder: Decoder[Digest] = Decoder.instance { hc =>
      (hc.get[String]("_value"), hc.get[String]("_algorithm")).mapN {
        case ("", "")           =>
          Right(NotComputedDigest)
        case (value, algorithm) =>
          DigestAlgorithm(algorithm)
            .map(ComputedDigest(_, value))
            .toRight(DecodingFailure(s"wrong DigestAlgorithm '$algorithm'", hc.history))
      }.flatten
    }

    deriveConfiguredDecoder[RemoteDiskStorageFileAttributes]
  }
}
// $COVERAGE-ON$
