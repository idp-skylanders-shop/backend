package app

import com.rabbitmq.client._
import upickle.default._

object EmailQueue {

  private val factory = new ConnectionFactory()
  factory.setHost(sys.env.getOrElse("RABBITMQ_HOST", "localhost"))
  factory.setUsername(sys.env.getOrElse("RABBITMQ_USER", ""))
  factory.setPassword(sys.env.getOrElse("RABBITMQ_PASS", ""))

  private lazy val connection = factory.newConnection()
  private lazy val channel = connection.createChannel()

  private val QUEUE = "email.queue"
  channel.queueDeclare(QUEUE, true, false, false, null)

  def enqueue(job: EmailJob): Unit = {
    val body = write(job).getBytes("UTF-8")
    channel.basicPublish(
      "",
      QUEUE,
      MessageProperties.PERSISTENT_TEXT_PLAIN,
      body
    )
  }
}
