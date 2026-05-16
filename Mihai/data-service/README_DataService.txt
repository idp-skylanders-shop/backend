=========================================================
  DATA SERVICE — Schelet Etapa 2
=========================================================

Responsabil: Mihai

Scop:
  Acest serviciu reprezinta inceputul decuplarii accesului
  la baza de date de logica de business din Backend Service.

Stare curenta (Etapa 2 — 40%):
  - Schelet creat (DataService.scala)
  - Structura de directoare pregatita
  - Conexiunea Postgres ramane momentan in backend-service

Plan Etapa 3 (100%):
  - Data Service va rula ca microserviciu separat (port 8081)
  - Va expune API REST intern (/internal/products, /internal/cart/...)
  - Backend Service va apela Data Service prin reteaua Docker interna
  - Baza de date nu va mai fi direct accesibila din afara Data Service
  - Veti putea scala Data Service independent de Backend Service

Tehnologii planificate:
  - Scala 3 + Cask (acelasi stack)
  - Quill + PostgreSQL (mutat din backend-service)
  - Retea Docker interna izolata (nu expusa prin Traefik/Kong)
=========================================================
