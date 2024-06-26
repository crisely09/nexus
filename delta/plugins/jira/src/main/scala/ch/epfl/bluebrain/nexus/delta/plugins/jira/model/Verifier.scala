package ch.epfl.bluebrain.nexus.delta.plugins.jira.model

import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

/**
  * The verifier code provided by the user allowing Delta to retrieve an access token for this user
  */
final case class Verifier(value: String)

object Verifier {

  implicit private val configuration: Configuration       = Configuration.default.withStrictDecoding
  implicit val verificationCodeDecoder: Decoder[Verifier] = deriveConfiguredDecoder[Verifier]
}
