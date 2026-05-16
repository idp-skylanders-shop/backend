package app

import java.nio.file.{Files, Paths}
import java.util.{Base64, UUID}
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap
import cask.MainRoutes
import app.EmailQueue
import app.EmailJob

// -------------------------------------------------------
// Prometheus-compatible metrics registry for Backend Service
// -------------------------------------------------------

object Metrics {
  private val counts = TrieMap[String, AtomicLong]()

  def inc(label: String): Unit =
    counts.getOrElseUpdate(label, new AtomicLong(0)).incrementAndGet()

  def toText: String = {
    val sb = new StringBuilder
    sb.append("# HELP backend_requests_total Total HTTP requests handled by Backend Service\n")
    sb.append("# TYPE backend_requests_total counter\n")
    counts.foreach { case (label, c) =>
      val safe = label.replace("\"", "\\\"")
      sb.append(s"""backend_requests_total{endpoint="$safe"} ${c.get}\n""")
    }
    sb.toString()
  }
}

// -------------------------------------------------------
// Backend Service
// Business logic only — all DB access delegated to Data Service
// -------------------------------------------------------

object App extends cask.MainRoutes {
  val replicaId: String = UUID.randomUUID().toString

  // Data Service internal URL — overridable via env
  val dataServiceUrl: String =
    sys.env.getOrElse("DATA_SERVICE_URL", "http://data-service:8082")

  override def host: String = "0.0.0.0"
  override def port: Int    = 8080

  private val cors = Seq("Access-Control-Allow-Origin" -> "*")

  // ---- Helpers: JWT parsing -----------------------------------------------

  def getUsername(request: cask.Request): String = {
    try {
      val authHeader = request.headers.get("authorization").flatMap(_.headOption)
      authHeader match {
        case Some(h) if h.startsWith("Bearer ") =>
          val parts = h.substring(7).split("\\.")
          if (parts.length >= 2) {
            val payload = new String(Base64.getUrlDecoder.decode(parts(1)))
            val data = ujson.read(payload)
            if (data.obj.contains("preferred_username")) data("preferred_username").str
            else "Unknown"
          } else "Unknown"
        case _ => "Guest"
      }
    } catch { case _: Exception => "Guest" }
  }

  def hasRole(request: cask.Request, role: String): Boolean = {
    try {
      val authHeader = request.headers.get("authorization").flatMap(_.headOption)
      authHeader match {
        case Some(h) if h.startsWith("Bearer ") =>
          val parts = h.substring(7).split("\\.")
          if (parts.length < 2) return false
          val payload = new String(Base64.getUrlDecoder.decode(parts(1)))
          val data = ujson.read(payload)
          if (data.obj.contains("realm_access"))
            data("realm_access")("roles").arr.map(_.str).contains(role)
          else false
        case _ => false
      }
    } catch { case _: Exception => false }
  }

  // ---- Health & Metrics ---------------------------------------------------

  @cask.get("/")
  def hello() =
    cask.Response("Hello from Skylanders Backend Service!", headers = cors)

  @cask.get("/metrics")
  def metrics() =
    cask.Response(
      Metrics.toText,
      headers = Seq("Content-Type" -> "text/plain; version=0.0.4; charset=utf-8")
    )

  // ---- Registration (calls Keycloak directly — no DB needed) --------------

  @cask.options("/api/register")
  def registerOptions() =
    cask.Response("", headers = Seq(
      "Access-Control-Allow-Origin"  -> "*",
      "Access-Control-Allow-Methods" -> "POST, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type"
    ))

  @cask.postJson("/api/register")
  def registerUser(
      username: ujson.Value,
      password: ujson.Value,
      role: ujson.Value = ujson.Str("user")
  ) = {
    Metrics.inc("POST /api/register")
    val user          = username.str
    val pass          = password.str
    val requestedRole = try { role.str } catch { case _: Exception => "user" }

    try {
      val tokenResp = requests.post(
        "http://keycloak:8080/realms/master/protocol/openid-connect/token",
        data = Map(
          "username"   -> sys.env.getOrElse("KC_ADMIN_USER", "admin"),
          "password"   -> sys.env.getOrElse("KC_ADMIN_PASS", "admin"),
          "grant_type" -> "password",
          "client_id"  -> "admin-cli"
        )
      )
      val adminToken  = ujson.read(tokenResp.text())("access_token").str
      val authHeaders = Map(
        "Authorization" -> s"Bearer $adminToken",
        "Content-Type"  -> "application/json"
      )

      val createResp = requests.post(
        "http://keycloak:8080/admin/realms/skylander_shop/users",
        data = ujson.Obj(
          "username"    -> user,
          "enabled"     -> true,
          "credentials" -> ujson.Arr(
            ujson.Obj("type" -> "password", "value" -> pass, "temporary" -> false)
          )
        ).render(),
        headers = authHeaders,
        check   = false
      )

      if (createResp.statusCode == 201) {
        if (requestedRole == "seller") {
          try {
            val userSearch = requests.get(
              s"http://keycloak:8080/admin/realms/skylander_shop/users?username=$user",
              headers = authHeaders
            )
            val userId = ujson.read(userSearch.text())(0)("id").str
            val roleData = ujson.read(
              requests.get("http://keycloak:8080/admin/realms/skylander_shop/roles/seller",
                headers = authHeaders).text()
            )
            requests.post(
              s"http://keycloak:8080/admin/realms/skylander_shop/users/$userId/role-mappings/realm",
              data = ujson.Arr(ujson.Obj("id" -> roleData("id").str, "name" -> roleData("name").str)).render(),
              headers = authHeaders
            )
          } catch {
            case e: Exception => println(s"Role assignment failed: ${e.getMessage}")
          }
        }
        cask.Response(ujson.Obj("success" -> true, "message" -> "User created"), headers = cors)

      } else if (createResp.statusCode == 409) {
        cask.Response(ujson.Obj("success" -> false, "message" -> "Username already exists"),
          statusCode = 409, headers = cors)

      } else {
        cask.Response(ujson.Obj("success" -> false, "message" -> "Keycloak Error"),
          statusCode = 500, headers = cors)
      }

    } catch {
      case e: Exception =>
        e.printStackTrace()
        cask.Response(ujson.Obj("success" -> false, "message" -> e.getMessage),
          statusCode = 500, headers = cors)
    }
  }

  // ---- Products (proxied to Data Service) ---------------------------------

  @cask.options("/api/products")
  def productsOptions() =
    cask.Response("", headers = Seq(
      "Access-Control-Allow-Origin"  -> "*",
      "Access-Control-Allow-Methods" -> "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type, Authorization"
    ))

  @cask.get("/api/products")
  def listProducts() = {
    Metrics.inc("GET /api/products")
    try {
      val resp = requests.get(s"$dataServiceUrl/internal/products")
      cask.Response(resp.text(), headers = cors)
    } catch {
      case e: Exception =>
        cask.Response(ujson.Obj("error" -> e.getMessage).render(), statusCode = 503, headers = cors)
    }
  }

  // Image saving stays here (filesystem operation tied to upload volume)
  @cask.post("/api/products")
  def addProduct(request: cask.Request) = {
    Metrics.inc("POST /api/products")
    try {
      val ownerName  = getUsername(request)
      val json       = ujson.read(request.text())
      val name       = json("name").str
      val price      = json("price").num
      val description = json("description").str
      val element    = if (json.obj.contains("element")) json("element").str else ""
      val series     = if (json.obj.contains("series")) json("series").str else ""
      val stock      = if (json.obj.contains("stock")) json("stock").num.toInt else 0

      var savedImagePath = ""
      if (json.obj.contains("image_data") && json("image_data").str.nonEmpty) {
        val base64String = json("image_data").str
        val cleanBase64  = base64String.split(",").last
        val imageBytes   = Base64.getDecoder.decode(cleanBase64)
        val extension    = if (name.contains(".")) name.substring(name.lastIndexOf(".")) else ".jpg"
        val filename     = s"${UUID.randomUUID().toString}$extension"
        val uploadDir    = Paths.get("/app/uploads")
        if (!Files.exists(uploadDir)) Files.createDirectories(uploadDir)
        Files.write(uploadDir.resolve(filename), imageBytes)
        savedImagePath = s"assets/$filename"
      }

      val payload = ujson.Obj(
        "name"        -> name,
        "element"     -> element,
        "series"      -> series,
        "price"       -> price,
        "stock"       -> stock,
        "image_url"   -> savedImagePath,
        "description" -> description,
        "owner"       -> ownerName
      )

      val resp = requests.post(
        s"$dataServiceUrl/internal/products",
        data    = payload.render(),
        headers = Map("Content-Type" -> "application/json"),
        check   = false
      )

      val respJson = ujson.read(resp.text())
      cask.Response(
        ujson.Obj("success" -> true, "id" -> respJson("id"), "replicaId" -> replicaId).render(),
        statusCode = resp.statusCode,
        headers    = cors
      )
    } catch {
      case e: Exception =>
        e.printStackTrace()
        cask.Response(ujson.Obj("error" -> e.getMessage).render(), statusCode = 500, headers = cors)
    }
  }

  @cask.options("/api/products/:id")
  def deleteOptions(id: Int) =
    cask.Response("", headers = Seq(
      "Access-Control-Allow-Origin"  -> "*",
      "Access-Control-Allow-Methods" -> "DELETE, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type, Authorization"
    ))

  @cask.delete("/api/products/:id")
  def deleteProduct(id: Int, request: cask.Request) = {
    Metrics.inc("DELETE /api/products/:id")
    val requester = getUsername(request)
    val isAdmin   = hasRole(request, "admin")

    try {
      val getResp = requests.get(s"$dataServiceUrl/internal/products/$id", check = false)

      if (getResp.statusCode == 404) {
        cask.Response(ujson.Obj("error" -> "Product not found").render(),
          statusCode = 404, headers = cors)
      } else {
        val product  = ujson.read(getResp.text())
        val owner    = product("owner").str
        val imageUrl = product("image_url").str
        val isOwner  = (owner == requester) && (requester != "Guest") && (requester != "Unknown")

        if (!isAdmin && !isOwner) {
          cask.Response(ujson.Obj("error" -> "Unauthorized: you must be Admin or Owner").render(),
            statusCode = 403, headers = cors)
        } else {
          // Delete associated image file from shared volume
          if (imageUrl != null && imageUrl.startsWith("assets/")) {
            try {
              val filename = imageUrl.stripPrefix("assets/")
              if (filename.nonEmpty) {
                val filePath = Paths.get("/app/uploads").resolve(filename)
                if (Files.exists(filePath) && !Files.isDirectory(filePath)) Files.delete(filePath)
              }
            } catch { case ex: Exception => println(s"Warning: could not delete file: ${ex.getMessage}") }
          }
          val delResp = requests.delete(s"$dataServiceUrl/internal/products/$id", check = false)
          cask.Response(delResp.text(), statusCode = delResp.statusCode, headers = cors)
        }
      }
    } catch {
      case e: Exception =>
        e.printStackTrace()
        cask.Response(ujson.Obj("error" -> e.getMessage).render(), statusCode = 500, headers = cors)
    }
  }

  // ---- Cart (proxied to Data Service) -------------------------------------

  @cask.options("/api/cart/add")
  def cartAddOptions() =
    cask.Response("", headers = Seq(
      "Access-Control-Allow-Origin"  -> "*",
      "Access-Control-Allow-Methods" -> "POST, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type, Authorization"
    ))

  @cask.post("/api/cart/add")
  def addToCart(request: cask.Request) = {
    Metrics.inc("POST /api/cart/add")
    try {
      val resp = requests.post(
        s"$dataServiceUrl/internal/cart/add",
        data    = request.text(),
        headers = Map("Content-Type" -> "application/json"),
        check   = false
      )
      cask.Response(resp.text(), statusCode = resp.statusCode, headers = cors)
    } catch {
      case e: Exception =>
        cask.Response(ujson.Obj("status" -> "error", "message" -> e.getMessage).render(),
          statusCode = 503, headers = cors)
    }
  }

  @cask.options("/api/cart/remove")
  def cartRemoveOptions() =
    cask.Response("", headers = Seq(
      "Access-Control-Allow-Origin"  -> "*",
      "Access-Control-Allow-Methods" -> "POST, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type, Authorization"
    ))

  @cask.post("/api/cart/remove")
  def removeFromCart(request: cask.Request) = {
    Metrics.inc("POST /api/cart/remove")
    try {
      val resp = requests.post(
        s"$dataServiceUrl/internal/cart/remove",
        data    = request.text(),
        headers = Map("Content-Type" -> "application/json"),
        check   = false
      )
      cask.Response(resp.text(), statusCode = resp.statusCode, headers = cors)
    } catch {
      case e: Exception =>
        cask.Response(ujson.Obj("status" -> "error", "message" -> e.getMessage).render(),
          statusCode = 503, headers = cors)
    }
  }

  @cask.options("/api/cart/items/:userId")
  def cartGetOptions(userId: String) =
    cask.Response("", headers = Seq(
      "Access-Control-Allow-Origin"  -> "*",
      "Access-Control-Allow-Methods" -> "GET, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type"
    ))

  @cask.get("/api/cart/items/:userId")
  def getCart(userId: String) = {
    Metrics.inc("GET /api/cart/items/:userId")
    try {
      val resp = requests.get(s"$dataServiceUrl/internal/cart/items/$userId")
      cask.Response(resp.text(), headers = cors)
    } catch {
      case e: Exception =>
        cask.Response(ujson.Obj("error" -> e.getMessage).render(), statusCode = 503, headers = cors)
    }
  }

  @cask.options("/api/cart/clear/:username")
  def clearCartOptions(username: String) =
    cask.Response("", headers = Seq(
      "Access-Control-Allow-Origin"  -> "*",
      "Access-Control-Allow-Methods" -> "POST, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type"
    ))

  @cask.post("/api/cart/clear/:username")
  def clearCart(username: String) = {
    Metrics.inc("POST /api/cart/clear/:username")
    try {
      val resp = requests.post(
        s"$dataServiceUrl/internal/cart/clear/$username",
        data    = "",
        headers = Map("Content-Type" -> "application/json"),
        check   = false
      )
      cask.Response(resp.text(), statusCode = resp.statusCode, headers = cors)
    } catch {
      case e: Exception =>
        cask.Response(ujson.Obj("status" -> "error", "message" -> e.getMessage).render(),
          statusCode = 503, headers = cors)
    }
  }

  @cask.options("/api/cart/checkout/:username")
  def checkoutOptions(username: String) =
    cask.Response("", headers = Seq(
      "Access-Control-Allow-Origin"  -> "*",
      "Access-Control-Allow-Methods" -> "POST, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type"
    ))

  @cask.post("/api/cart/checkout/:username")
  def checkout(username: String, request: cask.Request) = {
    Metrics.inc("POST /api/cart/checkout/:username")
    try {
      val resp = requests.post(
        s"$dataServiceUrl/internal/cart/checkout/$username",
        data    = "",
        headers = Map("Content-Type" -> "application/json"),
        check   = false
      )
      cask.Response(resp.text(), statusCode = resp.statusCode, headers = cors)
    } catch {
      case e: Exception =>
        cask.Response(ujson.Obj("status" -> "error", "message" -> e.getMessage).render(),
          statusCode = 503, headers = cors)
    }
  }

  // ---- Notifications (email via RabbitMQ — no DB) -------------------------

  @cask.options("/api/notify")
  def notifyOptions(request: cask.Request) =
    cask.Response("", headers = Seq(
      "Access-Control-Allow-Origin"  -> "*",
      "Access-Control-Allow-Methods" -> "POST, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type"
    ))

  @cask.post("/api/notify")
  def notifyUser(request: cask.Request) = {
    Metrics.inc("POST /api/notify")
    try {
      val body      = ujson.read(request.text())
      val userEmail = body("email").str
      val firstName = body("firstName").str
      val lastName  = body("lastName").str
      val address   = body("address").str
      val country   = body("country").str
      val subtotal  = body("total").num

      val shippingCost    = if (subtotal >= 100) 0.0 else 10.0
      val grandTotal      = subtotal + shippingCost
      val shippingDisplay = if (shippingCost == 0) "Free" else s"$$$shippingCost"

      val itemsHtml = if (body.obj.contains("items")) {
        body("items").arr.map { item =>
          val n        = item("name").str
          val p        = item("price").num
          val qty      = item("qty").num.toInt
          val itemTotal = p * qty
          s"""<tr>
            <td style="padding:8px 0;color:#555;border-bottom:1px solid #eee;">$n <span style="font-size:12px;color:#999;">(x$qty)</span></td>
            <td style="padding:8px 0;color:#555;text-align:right;border-bottom:1px solid #eee;">$$$itemTotal</td>
          </tr>"""
        }.mkString
      } else "<tr><td colspan='2'>No items details available</td></tr>"

      val emailBody = s"""<!DOCTYPE html><html><body style="margin:0;padding:0;font-family:Helvetica,Arial,sans-serif;background-color:#f4f4f4;">
        <div style="max-width:600px;margin:20px auto;background-color:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.05);">
          <div style="background-color:#0056b3;padding:25px;text-align:center;">
            <h1 style="color:#ffffff;margin:0;font-size:22px;">Order Confirmed!</h1>
          </div>
          <div style="padding:30px;">
            <p style="font-size:16px;color:#333;margin-top:0;">Hi <strong>$firstName</strong>,</p>
            <p style="font-size:15px;color:#666;">Thank you for shopping with Skylander Paradise Shop.</p>
            <table style="width:100%;border-collapse:collapse;font-size:14px;">
              $itemsHtml
              <tr>
                <td style="padding:8px 0;font-weight:bold;">Transport Fee</td>
                <td style="padding:8px 0;text-align:right;font-weight:bold;">$shippingDisplay</td>
              </tr>
            </table>
            <div style="margin-top:20px;font-size:18px;font-weight:bold;">Total: $$$grandTotal</div>
            <p style="color:#555;margin-top:15px;">Shipping to: $firstName $lastName, $address, $country</p>
          </div>
          <div style="background-color:#f8f8f8;padding:15px;text-align:center;font-size:12px;color:#888;border-top:1px solid #eee;">
            &copy; 2025 Skylander Paradise Shop Inc. | Bucharest, Romania
          </div>
        </div>
      </body></html>"""

      EmailQueue.enqueue(
        EmailJob(
          to      = userEmail,
          subject = s"Order Confirmation #${System.currentTimeMillis().toString.takeRight(5)}",
          content = emailBody
        )
      )

      cask.Response("Notification queued", headers = cors)
    } catch {
      case e: Exception =>
        cask.Response(s"Error: ${e.getMessage}", statusCode = 400, headers = cors)
    }
  }

  initialize()
}
