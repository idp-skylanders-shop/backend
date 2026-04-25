package app

import com.rabbitmq.client._
import upickle.default._
import app.EmailJob
import app.EmailService
import scala.util.{Try, Failure, Success}
import scala.concurrent.duration._
import Thread.sleep

object EmailWorker {

  def main(args: Array[String]): Unit = {
    println("Email worker started, waiting for messages...")

    val rabbitHost = sys.env.getOrElse("RABBITMQ_HOST", "rabbitmq")
    val factory = new ConnectionFactory()
    factory.setHost(rabbitHost)
    factory.setUsername("guest")
    factory.setPassword("guest")

    // rabbitmq dureaza ceva sa fie pornit
    // trebuie sa incercam de cateva ori pana reusim sa ne conectam
    // pentru a il astepta
    var connection: Connection = null
    while (connection == null) {
      Try(factory.newConnection()) match {
        case Success(conn) =>
          connection = conn
        case Failure(_) =>
          println(s"Not..ready..yet..retrying")
          sleep(3000)
      }
    }

    println("Connected to RabbitMQ!")
    val channel = connection.createChannel()

    val QUEUE = "email.queue"
    channel.queueDeclare(QUEUE, true, false, false, null)
    channel.basicQos(1)

    val callback = new DeliverCallback {
      override def handle(tag: String, delivery: Delivery): Unit = {
        val body = new String(delivery.getBody, "UTF-8")
        val job = read[EmailJob](body)

        println(s"Worker processing job for: ${job.to}")
        println(s"Content Preview: ${job.content.take(50)}...")

        try {
          EmailService.sendEmail(
            job.to,
            job.subject,
            job.content
          )
          channel.basicAck(delivery.getEnvelope.getDeliveryTag, false)
          println(s"Email sent to ${job.to}")
        } catch {
          case e: Exception =>
            e.printStackTrace()
            channel.basicNack(
              delivery.getEnvelope.getDeliveryTag,
              false,
              false
            )
        }
        // nu putem trimite mesaje infinite, asteptam 5 secunde
        // pentru a NU primi timeout de la MailTrap
        Thread.sleep(5000) 
      }
    }

    channel.basicConsume(QUEUE, false, callback, _ => {})
    while (true) Thread.sleep(10000)
  }
}
