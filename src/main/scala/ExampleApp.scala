import java.time.LocalDateTime
import java.io._

import scalaz.zio._
import scalaz.zio.console.{Console, _}
import com.softwaremill.sttp._
import com.softwaremill.sttp.asynchttpclient.zio.AsyncHttpClientZioBackend

object ExampleApp extends App {

  def run(args: List[String]): ZIO[Console, Nothing, StatusCode] =
    httpClientExample.fold[Int](_ => 1, _ => 0)

  def httpClientExample: ZIO[Console, Throwable, Unit] = {

    implicit val backend  = AsyncHttpClientZioBackend()
    val githubQuery = "http language:scala"
    val uri1 = uri"https://api.github.com/search/repositories?q=$githubQuery"

    val searchQuery = "scala +zio +cats-effect"
    val uri2 = uri"https://www.google.com/search?q=$searchQuery"
    val uri3 = uri"https://duckduckgo.com/search?q=$searchQuery"
    val request1: Request[String, Nothing] = sttp.get(uri1)
    val request2: Request[String, Nothing] = sttp.get(uri2)
    val request3: Request[String, Nothing] = sttp.get(uri3)
    val reqs = List(request1, request2, request3)

    for {

      _ <- log("BEGIN: Basic Sequential")
      _ <- timePrintWrite("Seq1")(request1)
      _ <- timePrintWrite("Seq2")(request2)
      _ <- log("END:   Basic Sequential\n")

      _ <- log("BEGIN: Fork Join with separate output effect")
      fiber1 <- timeTask(request1.send()).fork
      fiber2 <- timeTask(request2.send()).fork
      fiber3 <- timeTask(request3.send()).fork
      fiber = (fiber1 zip fiber2) zip fiber3
      tuple <- fiber.join
      _ <- printWrite("Fibre1", extractDate)(tuple._1._1)
      _ <- printWrite("Fibre2", extractDate)(tuple._1._2)
      _ <- printWrite("Fibre3", extractDate)(tuple._2)
      _ <- log("END: Fork Join with separate output effect\n")

      _ <- log("BEGIN: Fork Join with combined output effect")
      tfib1 <- timePrintWrite("Combined1")(request1).fork
      tfib2 <- timePrintWrite("Combined2")(request2).fork
      tfib3 <- timePrintWrite("Combined3")(request3).fork
      _ <- (tfib1 zip tfib2 zip tfib3).join
      _ <- log("END:   Fork Join with combined output effect\n")

      _ <- log("BEGIN: Parallel collect with separate foreach output")
      timed: List[Task[TimedResult[Response[String]]]] = reqs.map(_.send()).map(timeTask)
      all <- Task.collectAllPar(timed)
      _ <- ZIO.foreach(all.zipWithIndex)(printWriteI("Collect", extractDate))
      _ <- log("END:   Parallel collect with foreach output\n")

      _ <- log("BEGIN: Parallel foreach")
      _ <- ZIO.foreachPar(reqs.zipWithIndex)(timePrintWriteI("For"))
      _ <- log("END:   Parallel foreach\n")
    } yield backend.close()

  }

  val extractDate: Response[String] => Option[String] = r => r.header("Date")

  def timePrintWriteI(prefix: String)(req: (Request[String, Nothing], Int)): ZIO[Console, Throwable, Unit] = {
    timePrintWrite(s"$prefix${req._2 + 1}")(req._1)
  }

  def timePrintWrite(prefix: String)(req: Request[String, Nothing]): ZIO[Console, Throwable, Unit] = {
    implicit val backend  = AsyncHttpClientZioBackend()

    for {
      t <- timeTask(req.send())
      _ <- printWrite(prefix, extractDate)(t)
    } yield backend.close()
  }

  def printWriteI[Result, E](prefix: String, extract: Result => E)(tr: (TimedResult[Result], Int)): ZIO[Console, Throwable, Unit] = {
    printWrite(s"$prefix${tr._2 + 1}", extract)(tr._1)
  }

  def printWrite[Result, E](prefix: String, extract: Result => E)(tr: TimedResult[Result]): ZIO[Console, Throwable, Unit] = for {
    now <- Task(LocalDateTime.now)
    header = s"$now $prefix Start: '${tr.start}' End: '${tr.end}' Extract: '${extract(tr.result)}'"
    _ <- putStrLn(s"$header")
    filename = s"/tmp/zio-${prefix}-${now}.out"
    pw <- Task(new PrintWriter(new File(filename)))
    txt = s"$header\n\n${tr.result}"
    _ <- Task(pw.write(txt))
    _ <- Task(pw.close())
  } yield ()

  def log(msg: String): ZIO[Console, Throwable, Unit] = for {
    now <- Task(LocalDateTime.now)
    _ <- putStrLn(s"$now\t$msg")
  } yield ()

  def timeTask[Result](task: Task[Result]): Task[TimedResult[Result]] = for {
    t1 <- Task(LocalDateTime.now())
    r  <- task
    t2 <- Task(LocalDateTime.now())
  } yield TimedResult(t1, t2, r)

}

case class TimedResult[Result](start: LocalDateTime, end: LocalDateTime, result: Result)