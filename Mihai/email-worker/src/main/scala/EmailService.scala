package app

import org.apache.commons.mail.{DefaultAuthenticator, HtmlEmail}
import javax.activation.{CommandMap, MailcapCommandMap}
object EmailService {

  private val smtpHost = sys.env.getOrElse("SMTP_HOST", "sandbox.smtp.mailtrap.io")
  private val smtpPort = sys.env.getOrElse("SMTP_PORT", "2525").toInt
  private val smtpUser = sys.env.getOrElse("SMTP_USER", "")
  private val smtpPass = sys.env.getOrElse("SMTP_PASS", "")

  {
    val mc = new MailcapCommandMap()
    mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html")
    mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml")
    mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain")
    mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed")
    mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822")
    CommandMap.setDefaultCommandMap(mc)
  }

  // Va trimite un mail din partea system@skylander-paradise.com
  // Preferabil in format HTML daca serverul de SMTP suporta sata
  // ( va suporta in cazul nostru, dar am vrut sa emulez in caz real)
  def sendEmail(to: String, subject: String, content: String): Unit = {
    val email = new HtmlEmail()
    
    email.setHostName(smtpHost)
    email.setSmtpPort(smtpPort)
    email.setAuthenticator(new DefaultAuthenticator(smtpUser, smtpPass))
    email.setStartTLSEnabled(true)

    email.setFrom("system@skylander-paradise.com", "Skylander Shop Team")
    email.setSubject(subject)
    
    email.setCharset("UTF-8")
    email.setHtmlMsg(content)
    email.setTextMsg("Your email client does not support HTML messages.")

    email.addTo(to)
    email.send()
    
    println(s"Email sent successfully to $to")
  }
}