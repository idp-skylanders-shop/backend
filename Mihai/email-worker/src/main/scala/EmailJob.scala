package app

import upickle.default._

case class EmailJob(
  to: String,
  subject: String,
  content: String
)

object EmailJob {
  given ReadWriter[EmailJob] = macroRW
}