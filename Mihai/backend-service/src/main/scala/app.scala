package app
import io.getquill._
import io.getquill.PostgresJdbcContext
import upickle.default.{ReadWriter => RW, macroRW, write}
import cask.model.Response.Data.dataResponse
import io.getquill.autoQuote
import sourcecode.Text.generate
import java.nio.file.{Files, Paths}
import java.util.Base64
import cask.MainRoutes
import java.sql.SQLException
import org.apache.commons.mail._
import javax.activation.{CommandMap, MailcapCommandMap}
import com.rabbitmq.client._
import app.EmailQueue
import app.EmailJob
import org.apache.commons.mail.HtmlEmail

/*
  Doar partea de backend este suficient de autorizata
  sa se conecteze ca admin la servicii precum Keycloak.
  Frontend-ul trebuie sa faca cereri catre backend pentru
  astfel de operatiuni

  Este important ca backend-ul sa primeasca informatiile referitoare
  la ce produse cumpara un client pentru a putea actualiza in mod sigur
  baza de date
*/

case class CartItem(
  id: Int,
  userId: String,
  productId: Int
)
object CartItem {
  implicit val rw: RW[CartItem] = macroRW
}

inline def cartItems = quote {
  querySchema[CartItem]("cart_items")
}

case class Skylander(
    id: Int, 
    name: String, 
    element: String, 
    series: String, 
    price: Double, 
    stock: Int, 
    image_url: String, 
    description: String,
    owner: String
)
object Skylander {
  implicit val rw: RW[Skylander] = macroRW
}

/*
  Serviciu folosit ca o coada de mesaje pentru a trimite mesajele asincron
  Important, pentru a nu bloca clientul cateva secunde pana se trimite un mail
*/

object RabbitMQ {
  private val factory = new ConnectionFactory()
  factory.setHost("rabbitmq")
  // parola este setata din docker secrets
  // pentru o securitate maxima
  factory.setUsername(sys.env.getOrElse("RABBITMQ_USER", ""))
  factory.setPassword(sys.env.getOrElse("RABBITMQ_PASS", ""))

  private val connection = factory.newConnection()
  private val channel = connection.createChannel()

  private val QUEUE = "email.queue"

  channel.queueDeclare(QUEUE, true, false, false, null)

  def publishEmail(job: EmailJob): Unit = {
    val body = write(job).getBytes("UTF-8")
    channel.basicPublish(
      "",
      QUEUE,
      MessageProperties.PERSISTENT_TEXT_PLAIN,
      body
    )
  }
}

object App extends cask.MainRoutes {
  val replicaId: String = java.util.UUID.randomUUID().toString
  val smtpHost = "sandbox.smtp.mailtrap.io"
  val smtpPort = 587 // port comun pentru MailTrap
  // user-ul si parola pentru MailTrap din consola de admin
  val smtpUser = sys.env.getOrElse("smtp_user", "")
  val smtpPass = sys.env.getOrElse("smtp_pass", "")
  override def host: String = "0.0.0.0"
  override def port: Int = 8080

  lazy val ctx = new PostgresJdbcContext(SnakeCase, "ctx")
  import ctx._

  inline def skylanders = quote {
    querySchema[Skylander]("skylanders")
  }

  @cask.get("/")
  def hello() = {
    cask.Response(
      data = "Hello from Scala Backend!",
      headers = Seq("Access-Control-Allow-Origin" -> "*")
    )
  }

  // Optiunile sunt fundamentale in Scala, fara ele primim eroare de CORS
  // de la browser
  @cask.options("/api/register")
  def registerOptions() = {
    cask.Response(
      "",
      headers = Seq(
        "Access-Control-Allow-Origin" -> "*",
        "Access-Control-Allow-Methods" -> "POST, OPTIONS",
        "Access-Control-Allow-Headers" -> "Content-Type"
      )
    )
  }

  /*
    Pentru a crea un nou user, Scala se va autentifica ca admin-cli la keycloak pentru
    a primi un token pe care il poate folosi la crearea unui nou user
    
    Si ii va trimite o cerere POST cu user-ul si parola noua

    Daca user-ul a selectat optiunea de a vinde produse va primi rolul de seller
  */
  @cask.postJson("/api/register")
  def registerUser(username: ujson.Value, password: ujson.Value, role: ujson.Value = ujson.Str("user")) = {
    
    val user = username.str
    val pass = password.str
    val requestedRole = try { role.str } catch { case _: Exception => "user" }

    try {
      val tokenResp = requests.post(
        "http://keycloak:8080/realms/master/protocol/openid-connect/token",
        data = Map(
          "username" -> sys.env.getOrElse("KC_ADMIN_USER", "admin"), 
          "password" -> sys.env.getOrElse("KC_ADMIN_PASS", "admin"),
          "grant_type" -> "password",
          "client_id"  -> "admin-cli"
        )
      )
      val adminToken = ujson.read(tokenResp.text())("access_token").str
      val authHeaders = Map(
        "Authorization" -> s"Bearer $adminToken",
        "Content-Type"  -> "application/json"
      )
      val newUserPayload = ujson.Obj(
        "username" -> user,
        "enabled"  -> true,
        "credentials" -> ujson.Arr(
          ujson.Obj("type" -> "password", "value" -> pass, "temporary" -> false)
        )
      )

      val createResp = requests.post(
        "http://keycloak:8080/admin/realms/skylander_shop/users",
        data = newUserPayload.render(),
        headers = authHeaders,
        check = false
      )

      if (createResp.statusCode == 201) {
        if (requestedRole == "seller") {
            try {
                val userSearch = requests.get(
                    s"http://keycloak:8080/admin/realms/skylander_shop/users?username=$user",
                    headers = authHeaders
                )
                val userId = ujson.read(userSearch.text())(0)("id").str
                val roleSearch = requests.get(
                    "http://keycloak:8080/admin/realms/skylander_shop/roles/seller",
                    headers = authHeaders
                )
                val roleId = ujson.read(roleSearch.text())("id").str
                val roleName = ujson.read(roleSearch.text())("name").str
                val roleMappingPayload = ujson.Arr(
                    ujson.Obj("id" -> roleId, "name" -> roleName)
                )

                requests.post(
                    s"http://keycloak:8080/admin/realms/skylander_shop/users/$userId/role-mappings/realm",
                    data = roleMappingPayload.render(),
                    headers = authHeaders
                )
                println(s"Assigned 'seller' role to $user")

            } catch {
                case e: Exception => 
                    println(s"User created but Role assignment failed: ${e.getMessage}")
            }
        }

        cask.Response(
          ujson.Obj("success" -> true, "message" -> "User created"),
          headers = Seq("Access-Control-Allow-Origin" -> "*")
        )
      } else if (createResp.statusCode == 409) {
        cask.Response(
          ujson.Obj("success" -> false, "message" -> "Username already exists"),
          statusCode = 409,
          headers = Seq("Access-Control-Allow-Origin" -> "*")
        )
      } else {
        println("Keycloak FAILED: " + createResp.text())
        cask.Response(
          ujson.Obj("success" -> false, "message" -> "Keycloak Error"),
          statusCode = 500,
          headers = Seq("Access-Control-Allow-Origin" -> "*")
        )
      }

    } catch {
      case e: Exception =>
        e.printStackTrace()
        cask.Response(
          ujson.Obj("success" -> false, "message" -> e.getMessage),
          statusCode = 500,
          headers = Seq("Access-Control-Allow-Origin" -> "*")
        )
    }
  }

  @cask.options("/api/products")
  def productsOptions() = {
    cask.Response("", headers = Seq(
      "Access-Control-Allow-Origin" -> "*",
      "Access-Control-Allow-Methods" -> "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type, Authorization"
    ))
  }

  @cask.get("/api/products")
  def listProducts() = {
    val products = ctx.run(skylanders)
    
    cask.Response(
      data = write(products),
      headers = Seq("Access-Control-Allow-Origin" -> "*")
    )
  }

  /*
    Adaugarea unei imagini a fost mult mai problematica decat ar fi trebuit.
    Din cauza limitarii impuse de Cask pe versiunea 3 de Scala.

    Workaround-ul a fost ca imaginea sa fie trimisa ca un string de caractere
    in base64. Desi nu este cel mai eficient sau elegant de a face asta, este
    singurul mod de a o face cu Cask. ( imaginea este salvata in volumul extern frontend/public/assets).
    Si stearsa automat cand un user elimina un produs
  */
  @cask.post("/api/products")
  def addProduct(request: cask.Request) = {
    println("AUTH HEADER: " + request.headers.get("Authorization"))
    try {
      val ownerName = getUsername(request) 

      val requestBody = request.text()
      val json = ujson.read(requestBody)
      val name = json("name").str
      val price = json("price").num
      val description = json("description").str
      val element = if (json.obj.contains("element")) json("element").str else ""
      val series = if (json.obj.contains("series")) json("series").str else ""
      val stock = if (json.obj.contains("stock")) json("stock").num.toInt else 0

      var savedImagePath = "" 
      if (json.obj.contains("image_data") && json("image_data").str.nonEmpty) {
        val base64String = json("image_data").str
        val cleanBase64 = base64String.split(",").last 
        val imageBytes = Base64.getDecoder.decode(cleanBase64)
        // foarte important, sa se foloseasca un id random in crearea produsului
        // daca as folosi doar millis cum faceam pe o replica, 2 imagini s-ar putea suprapune
        // si sterge intre ele
        import java.util.UUID
        val extension = if (name.contains(".")) name.substring(name.lastIndexOf(".")) else ".jpg"
        val filename = s"${UUID.randomUUID().toString}$extension"
        val uploadDir = Paths.get("/app/uploads")
        if (!Files.exists(uploadDir)) Files.createDirectories(uploadDir)
        val filePath = uploadDir.resolve(filename)
        Files.write(filePath, imageBytes)
        savedImagePath = s"assets/$filename"
      }
      val newSkylander = Skylander(
        0, name, element, series, price, stock, savedImagePath, description, 
        ownerName
      )
      
      val id = ctx.run(
        skylanders.insertValue(lift(newSkylander)).returningGenerated(_.id)
      )

      cask.Response(
      ujson.Obj(
        "success" -> true,
        "id" -> id,
        "replicaId" -> replicaId
      ),
      headers = Seq("Access-Control-Allow-Origin" -> "*")
    )
    } catch {
      case e: Exception =>
        e.printStackTrace()
        cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500, headers = Seq("Access-Control-Allow-Origin" -> "*"))
    }
  }

/*
  Functie ajutatoare care trebuie sa afle numele user-ului conectat
  Important pentru cateva aspecte: adaugarea unui produs, verificarea daca un user
  poate sterge un produs sau nu, validarea cui a incarcat produsul.
*/

def getUsername(request: cask.Request): String = {
    try {
      val authHeader = request.headers.get("authorization").flatMap(_.headOption)
      authHeader match {
        case Some(header) if header.startsWith("Bearer ") =>
          val token = header.substring(7)
          val parts = token.split("\\.")
          if (parts.length >= 2) {
             val payloadJson = new String(Base64.getUrlDecoder.decode(parts(1)))
             val data = ujson.read(payloadJson)
             if (data.obj.contains("preferred_username")) data("preferred_username").str
             else "Unknown"
          } else "Unknown"
        case _ => "Guest"
      }
    } catch {
      case _: Exception => "Guest"
    }
  }
  

// Intoarce rolul pe care un user il are pe keycloak

def hasRole(request: cask.Request, roleToCheck: String): Boolean = {
  try {
    val authHeader = request.headers.get("authorization").flatMap(_.headOption)
    
    authHeader match {
      case Some(header) if header.startsWith("Bearer ") =>
        val token = header.substring(7)
        val parts = token.split("\\.")
        if (parts.length < 2) return false
        
        val payloadJson = new String(Base64.getUrlDecoder.decode(parts(1)))
        val data = ujson.read(payloadJson)

        if (data.obj.contains("realm_access")) {
            val roles = data("realm_access")("roles").arr.map(_.str)
            roles.contains(roleToCheck)
        } else {
          false
        }
      case _ => false
    }
  } catch {
    case _: Exception => false
  }
}

  @cask.options("/api/products/:id")
  def deleteOptions(id: Int) = {
    cask.Response(
      "",
      headers = Seq(
        "Access-Control-Allow-Origin" -> "*",
        "Access-Control-Allow-Methods" -> "DELETE, OPTIONS",
        "Access-Control-Allow-Headers" -> "Content-Type, Authorization"
      )
    )
  }

  /*
    Functie care sterge un produs din baza de date
    Un produs poate sa fie sters doar de admin sau de persoana care l-a adaugat
  */

  @cask.delete("/api/products/:id")
  def deleteProduct(id: Int, request: cask.Request) = {
    val requester = getUsername(request)
    val userIsAdmin = hasRole(request, "admin")

    try {
      val product = ctx.run(skylanders.filter(_.id == lift(id))).headOption
      
      product match {
        case Some(p) =>
          val isOwner = (p.owner == requester) && (requester != "Guest") && (requester != "Unknown")
          
          if (userIsAdmin || isOwner) {
            if (p.image_url != null && p.image_url.startsWith("assets/")) {
                try {
                    val filename = p.image_url.stripPrefix("assets/")
                    
                    if (filename.nonEmpty) {
                        val filePath = Paths.get("/app/uploads").resolve(filename)
                        
                        if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                            Files.delete(filePath)
                            println(s"Deleted file from disk: $filePath")
                        } else {
                            println(s"File not found on disk, skipping: $filePath")
                        }
                    }
                } catch {
                    case ex: Exception => 
                        println(s"Warning: Could not delete file: ${ex.getMessage}")
                }
            }
            ctx.run(skylanders.filter(_.id == lift(id)).delete)
            
            cask.Response(ujson.Obj("success" -> true), headers = Seq("Access-Control-Allow-Origin" -> "*"))

          } else {
            cask.Response(
              ujson.Obj("error" -> "Unauthorized: You must be Admin or the Owner"), 
              statusCode = 403, 
              headers = Seq("Access-Control-Allow-Origin" -> "*")
            )
          }

        case None =>
          cask.Response(ujson.Obj("error" -> "Product not found"), statusCode = 404, headers = Seq("Access-Control-Allow-Origin" -> "*"))
      }
    } catch {
      case e: Exception =>
        e.printStackTrace()
        cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500, headers = Seq("Access-Control-Allow-Origin" -> "*"))
    }
  }

@cask.options("/api/cart/add")
def cartAddOptions() = {
  cask.Response(
    "",
    headers = Seq(
      "Access-Control-Allow-Origin" -> "*",
      "Access-Control-Allow-Methods" -> "POST, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type, Authorization"
    )
  )
}

@cask.post("/api/cart/add") 
def addToCart(request: cask.Request) = {
  try {
    val jsonString = request.text()
    val json = ujson.read(jsonString)
    
    val productId = json("productId").num.toInt
    val userId = json("userId").str

    ctx.transaction {
      // for update va da lock la acest ELEMENT
      // pentru a NU avea problema cu concurenta daca doi utilizatori
      // incearca sa cumpere acelasi produs in acelasi timp

      // nu merge ORM aici, trebuie SQL direct.. din nefericire Scala 3 este instabil
      // in contexte de Cask
      val maybeProduct = ctx.run(
        sql"SELECT * FROM skylanders WHERE id = ${lift(productId)} FOR UPDATE"
        .as[Query[Skylander]]
      ).headOption

      maybeProduct match {
        case Some(p) if p.stock > 0 =>
          ctx.run(quote {
            skylanders.filter(p => p.id == lift(productId))
                      .update(p => p.stock -> (p.stock - 1))
          })
          ctx.run(quote {
            cartItems.insert(
              _.userId -> lift(userId),
              _.productId -> lift(productId)
            )
          })

          cask.Response(
            data = ujson.Obj("status" -> "success").render(),
            headers = Seq("Access-Control-Allow-Origin" -> "*"),
            statusCode = 200
          )

        case Some(_) =>
          cask.Response(
            data = ujson.Obj("status" -> "error", "message" -> "Out of stock").render(),
            headers = Seq("Access-Control-Allow-Origin" -> "*"),
            statusCode = 400
          )

        case None =>
          cask.Response(
            data = ujson.Obj("status" -> "error", "message" -> "Product not found").render(),
            headers = Seq("Access-Control-Allow-Origin" -> "*"),
            statusCode = 404
          )
      }
    }
  } catch {
    case e: Exception =>
      println(s"ERROR in /api/cart/add: ${e.getMessage}")
      e.printStackTrace()
      cask.Response(
        data = ujson.Obj("status" -> "error", "message" -> e.getMessage).render(),
        headers = Seq("Access-Control-Allow-Origin" -> "*"),
        statusCode = 500
      )
  }
}
@cask.options("/api/cart/remove")
def cartRemoveOptions() = {
  cask.Response(
    "",
    headers = Seq(
      "Access-Control-Allow-Origin" -> "*",
      "Access-Control-Allow-Methods" -> "POST, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type, Authorization"
    )
  )
}

@cask.post("/api/cart/remove")
def removeFromCart(request: cask.Request) = {
  try {
    val jsonString = request.text()
    val json = ujson.read(jsonString)

    val productId = json("productId").num.toInt
    val cartId = if (json.obj.contains("cartId")) Some(json("cartId").num.toInt) else None

    if (cartId.isEmpty) {
      throw new Exception("Missing cartId: Cannot remove item without ID")
    }

    ctx.transaction {
      ctx.run(quote { 
        cartItems.filter(c => c.id == lift(cartId.get)).delete 
      })
      ctx.run(quote {
        skylanders.filter(p => p.id == lift(productId))
                  .update(p => p.stock -> (p.stock + 1))
      })
    }

    cask.Response(
      data = ujson.Obj("status" -> "success").render(),
      headers = Seq("Access-Control-Allow-Origin" -> "*"),
      statusCode = 200
    )

  } catch {
    case e: Exception =>
      println(s"ERROR in /api/cart/remove: ${e.getMessage}")
      e.printStackTrace()
      cask.Response(
        data = ujson.Obj("status" -> "error", "message" -> e.getMessage).render(),
        headers = Seq("Access-Control-Allow-Origin" -> "*"),
        statusCode = 500
      )
  }
}
@cask.options("/api/cart/items/:userId")
def cartGetOptions(userId: String) = {
  cask.Response("", headers = Seq(
    "Access-Control-Allow-Origin" -> "*",
    "Access-Control-Allow-Methods" -> "GET, OPTIONS",
    "Access-Control-Allow-Headers" -> "Content-Type"
  ))
}

@cask.get("/api/cart/items/:userId")
def getCart(userId: String) = {
  try {
    val items = ctx.run(quote {
      for {
        c <- cartItems.filter(c => c.userId == lift(userId))
        p <- skylanders if p.id == c.productId
      } yield (c, p)
    })

    val json = items.map { case (c, p) =>
      ujson.Obj(
        "cartId" -> c.id,        
        "productId" -> p.id,
        "name" -> p.name,
        "price" -> p.price,
        "stock" -> p.stock
      )
    }

    cask.Response(
      data = ujson.Arr.from(json).render(),
      headers = Seq("Access-Control-Allow-Origin" -> "*")
    )
  } catch {
    case e: Exception =>
      cask.Response(
        data = ujson.Obj("error" -> e.getMessage).render(),
        headers = Seq("Access-Control-Allow-Origin" -> "*"),
        statusCode = 500
      )
  }
}

// Daca un utilizator se deconecteaza de pe site, cosul lui de cumparaturi va fi
// sters, pentru a nu bloca produse pentru alti users

  @cask.options("/api/cart/clear/:username")
  def clearCartOptions(username: String) = {
    cask.Response(
      "",
      headers = Seq(
        "Access-Control-Allow-Origin" -> "*",
        "Access-Control-Allow-Methods" -> "POST, OPTIONS",
        "Access-Control-Allow-Headers" -> "Content-Type"
      ),
      statusCode = 200
    )
  }

  @cask.post("/api/cart/clear/:username")
  def clearCart(username: String) = {
    try {
      ctx.transaction {
        val userItems = ctx.run(quote {
          cartItems.filter(c => c.userId == lift(username))
        })
        for (item <- userItems) {
          ctx.run(quote {
            skylanders.filter(p => p.id == lift(item.productId))
                      .update(p => p.stock -> (p.stock + 1))
          })
        }
        ctx.run(quote {
          cartItems.filter(c => c.userId == lift(username)).delete
        })
      }
      cask.Response(
        data = ujson.Obj("status" -> "success", "message" -> "Cart cleared").render(),
        headers = Seq("Access-Control-Allow-Origin" -> "*"),
        statusCode = 200
      )

    } catch {
      case e: Exception =>
        println(s"ERROR in /api/cart/clear: ${e.getMessage}")
        e.printStackTrace()
        cask.Response(
          data = ujson.Obj("status" -> "error", "message" -> e.getMessage).render(),
          headers = Seq("Access-Control-Allow-Origin" -> "*"),
          statusCode = 500
        )
    }
  }


// Functie care trimite un mail folosindu-se de MailTrap
import org.apache.commons.mail.HtmlEmail

import org.apache.commons.mail.{HtmlEmail, DefaultAuthenticator}

def sendEmail(to: String, subject: String, htmlContent: String): String = {
  try {
    val email = new HtmlEmail()

    email.setHostName(smtpHost)
    email.setSmtpPort(smtpPort)
    email.setAuthenticator(new DefaultAuthenticator(smtpUser, smtpPass))
    email.setStartTLSEnabled(true)

    email.setFrom("system@skylander.com", "Skylander Paradise Shop Team")
    email.setSubject(subject)

    email.setCharset("UTF-8")
    email.setHtmlMsg(htmlContent)
    email.setTextMsg("Your email client does not support HTML messages")

    email.addTo(to)
    email.send()

    "Email sent successfully!"
  } catch {
    case e: Exception =>
      e.printStackTrace()
      s"Failed to send email: ${e.getMessage}"
  }
}


  @cask.options("/api/notify")
  def notifyOptions(request: cask.Request) = {
    cask.Response(
      "",
      headers = Seq(
        ("Access-Control-Allow-Origin", "*"),
        ("Access-Control-Allow-Methods", "POST, OPTIONS"),
        ("Access-Control-Allow-Headers", "Content-Type")
      )
    )
  }

  /*
    Va construi email-ul trimis catre user, trebuie sa calculeze pretul
    cosului si sa adauge transportul daca este sub 100 de dolari.

    Email-ul va fi trimis ca un header HTML pentru a il prezenta intr-un mod
    profesional
  */

  @cask.post("/api/notify")
  def notifyUser(request: cask.Request) = {
    try {
      val body = ujson.read(request.text())
      val userEmail = body("email").str
      val firstName = body("firstName").str
      val lastName = body("lastName").str
      val address = body("address").str
      val country = body("country").str
      val subtotal = body("total").num 

      val shippingCost = if (subtotal >= 100) 0.0 else 10.0
      val grandTotal = subtotal + shippingCost
      
      val shippingDisplay = if (shippingCost == 0) "Free" else s"$$$shippingCost"
      val itemsHtml = if (body.obj.contains("items")) {
        body("items").arr.map { item =>
          val name = item("name").str
          val price = item("price").num
          val qty = item("qty").num.toInt
          val itemTotal = price * qty
          
          s"""
          <tr>
            <td style="padding: 8px 0; color: #555; border-bottom: 1px solid #eee;">$name <span style="font-size: 12px; color: #999;">(x$qty)</span></td>
            <td style="padding: 8px 0; color: #555; text-align: right; border-bottom: 1px solid #eee;">$$${itemTotal}</td>
          </tr>
          """
        }.mkString
      } else {
        "<tr><td colspan='2'>No items details available</td></tr>"
      }
      val emailBody = s"""
        <!DOCTYPE html>
        <html>
        <body style="margin: 0; padding: 0; font-family: Helvetica, Arial, sans-serif; background-color: #f4f4f4;">
          
          <div style="max-width: 600px; margin: 20px auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
            
            <div style="background-color: #0056b3; padding: 25px; text-align: center;">
              <h1 style="color: #ffffff; margin: 0; font-size: 22px; letter-spacing: 0.5px;">Order Confirmed!</h1>
            </div>

            <div style="padding: 30px;">
              
              <p style="font-size: 16px; color: #333; margin-top: 0;">Hi <strong>$firstName</strong>,</p>
              <p style="font-size: 15px; color: #666; line-height: 1.5; margin-bottom: 25px;">
                Thank you for shopping with Skylander Paradise Stop. We have received your order and are getting it ready for shipment.
              </p>

              <div style="margin-bottom: 25px;">
                <h3 style="font-size: 16px; color: #333; margin-bottom: 10px; border-bottom: 2px solid #eee; padding-bottom: 5px;">Items Ordered</h3>
                <table style="width: 100%; border-collapse: collapse; font-size: 14px;">
                  
                  $itemsHtml

                  <tr>
                    <td style="padding: 8px 0; color: #555; font-weight: bold; border-bottom: 1px solid #eee;">Transport Fee</td>
                    <td style="padding: 8px 0; color: #555; text-align: right; font-weight: bold; border-bottom: 1px solid #eee;">$shippingDisplay</td>
                  </tr>

                </table>
              </div>

              <div style="background-color: #fff; border: 1px solid #e0e0e0; border-radius: 6px; padding: 20px;">
                <h3 style="margin-top: 0; color: #333; font-size: 16px; border-bottom: 1px solid #eee; padding-bottom: 10px; margin-bottom: 15px;">Shipping Details</h3>
                
                <table style="width: 100%; border-collapse: collapse; font-size: 14px;">
                  <tr>
                    <td style="padding: 6px 0; color: #777;"><strong>Name:</strong></td>
                    <td style="padding: 6px 0; color: #333; text-align: right;">$firstName $lastName</td>
                  </tr>
                  <tr>
                    <td style="padding: 6px 0; color: #777;"><strong>Address:</strong></td>
                    <td style="padding: 6px 0; color: #333; text-align: right;">$address</td>
                  </tr>
                  <tr>
                    <td style="padding: 6px 0; color: #777;"><strong>Country:</strong></td>
                    <td style="padding: 6px 0; color: #333; text-align: right;">$country</td>
                  </tr>
                  
                  <tr>
                    <td style="padding-top: 15px; border-top: 1px solid #eee; margin-top: 15px; font-weight: bold; color: #333; font-size: 16px;">Total Paid:</td>
                    <td style="padding-top: 15px; border-top: 1px solid #eee; margin-top: 15px; font-weight: bold; color: #28a745; text-align: right; font-size: 16px;">$$${grandTotal}</td>
                  </tr>
                </table>
              </div>

              <p style="font-size: 13px; color: #999; text-align: center; margin-top: 30px;">
                You will receive another email when your order ships.
              </p>
            </div>

            <div style="background-color: #f8f8f8; padding: 15px; text-align: center; font-size: 12px; color: #888; border-top: 1px solid #eee;">
              &copy; 2025 Skylander Paradise Shop Inc. | Bucharest, Romania
            </div>
          </div>
        </body>
        </html>
      """

      EmailQueue.enqueue(
        EmailJob(
          to = userEmail,
          subject = s"Order Confirmation #${System.currentTimeMillis().toString.takeRight(5)}",
          content = emailBody
        )
      )

      cask.Response("Notification queued", headers = Seq(("Access-Control-Allow-Origin", "*")))
    } catch {
      case e: Exception =>
        cask.Response(s"Error: ${e.getMessage}", statusCode = 400, headers = Seq(("Access-Control-Allow-Origin", "*")))
    }
  }

  @cask.options("/api/cart/checkout/:username")
  def checkoutOptions(username: String) = {
    cask.Response(
      "",
      headers = Seq(
        ("Access-Control-Allow-Origin", "*"),
        ("Access-Control-Allow-Methods", "POST, OPTIONS"),
        ("Access-Control-Allow-Headers", "Content-Type")
      )
    )
  }

  // Functie care face procesul de cumparare
  // Va updata stock-urile pentru produsele afectate
  // Dupa care se va trimite un mail de confirmare
  @cask.post("/api/cart/checkout/:username")
  def checkout(username: String, request: cask.Request) = {
    try {
      ctx.transaction {
        ctx.run(quote {
          cartItems.filter(c => c.userId == lift(username)).delete
        })
      }

      cask.Response(
        data = ujson.Obj("status" -> "success", "message" -> "Order placed").render(),
        headers = Seq(
          ("Access-Control-Allow-Origin", "*"),
          ("Content-Type", "application/json")
        ),
        statusCode = 200
      )
    } catch {
      case e: Exception =>
        cask.Response(
          data = ujson.Obj("status" -> "error", "message" -> e.getMessage).render(),
          headers = Seq(("Access-Control-Allow-Origin", "*")),
          statusCode = 500
        )
    }
  }

  initialize()
}


