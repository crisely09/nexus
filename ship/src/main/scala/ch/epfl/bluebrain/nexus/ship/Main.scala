package ch.epfl.bluebrain.nexus.ship

import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.ship.BuildInfo
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig
import ch.epfl.bluebrain.nexus.ship.model.InputEvent
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.Stream
import fs2.io.file.{Files, Path}
import io.circe.parser._

object Main
    extends CommandIOApp(
      name = "ship",
      header = "Nexus Ship",
      version = BuildInfo.version
    ) {

  private val logger = Logger[Main.type]

  private val inputFile: Opts[Path] = Opts.option[String]("file", help = "The data file containing the imports.").map(Path(_))

  private val configFile: Opts[Option[Path]] = Opts.option[String]("config", help = "The configuration file.").map(Path(_)).orNone

  private val offset: Opts[Offset] = Opts
    .option[Long]("offset", help = "To perform an incremental import from the provided offset.")
    .map(Offset.at).withDefault(Offset.start)

  private val runProcess = Opts.subcommand("run", "Run an import") {
    (inputFile, configFile, offset).mapN(Run)
  }

  override def main: Opts[IO[ExitCode]] = runProcess.map {
    case Run(file, config, offset) =>
      for {
        _ <- logger.info(s"Running the import with file $file, config $config and from offset $offset")
        config <- ShipConfig.load(config)
        events = eventStream(file)
        _ <- Transactors.init(config.database).use {
          _ => EventProcessor.run(List.empty, events)
        }
      } yield ExitCode.Success
  }

  private def eventStream(file: Path): Stream[IO, InputEvent] = Files[IO].readUtf8Lines(file).map(decode[InputEvent]).rethrow

  private final case class Run(file: Path, config: Option[Path], offset: Offset)

}