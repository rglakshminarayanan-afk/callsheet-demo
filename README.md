# CallSheet (demo)

Actor availability and meeting scheduler for Bollywood managers and production houses.
This is a **static-data demo**: Spring Boot 3 backend (in-memory data), plain HTML/CSS/JS front end
served by the same app. No database, no real login.

## Try it locally

You need Java 17+ and Maven.

```bash
mvn spring-boot:run
# open http://localhost:8080
```

Or with Docker:

```bash
docker build -t callsheet .
docker run -p 8080:8080 callsheet
```

Run the access-rule tests: `mvn test`

## What the demo shows

**Actor manager** (sign in as Rajiv Menon, Sunita Rao or Farah Siddiqui)
- Sees only the actors they manage
- Marks shooting / vacation / available dates on a calendar
- Answers meeting requests: accept, decline, or propose a new date
- Sends their own meeting request, with the next 60 days of availability attached

**Production house** (sign in as Neha Kapoor, Anjali Verma or Imran Sheikh)
- Searches talent by name, genre, language, city, and free date range
- Reads availability on a 4-week schedule strip (busy or free only, never the reason)
- Clicks a free day to propose a meeting
- Tracks every request: awaiting reply, new date proposed, confirmed, declined
- Downloads a calendar invite (.ics) once accepted

Neha Kapoor and Anjali Verma are in the same company and see the same requests.

## Deploy it free so you can share a link (Render)

1. Create a GitHub account if needed, then create a new repository and push this folder:
   ```bash
   git init && git add . && git commit -m "CallSheet demo"
   git branch -M main
   git remote add origin https://github.com/<you>/callsheet-demo.git
   git push -u origin main
   ```
2. Go to https://render.com and sign in with GitHub.
3. **New > Blueprint**, pick the repository. Render reads `render.yaml`. (Or **New > Web Service**,
   runtime **Docker**, plan **Free**, health check path `/actuator/health`.)
4. Wait for the first build (about 5 minutes). Your link looks like `https://callsheet-demo.onrender.com`.

Things to know about the free tier:
- The service **sleeps after ~15 minutes idle**. The first visit after that takes 30 to 60 seconds. Open the link yourself a minute before a demo.
- Data lives in memory, so it **resets on every restart**. There is also a "Reset demo data" button in the footer.
- Everyone using the link shares the same data. Fine for a walkthrough, not for several parallel pilots.

Other hosts that run the same Dockerfile: Railway, Fly.io, Koyeb, Google Cloud Run.

## Project layout

```
src/main/java/com/callsheet/
  CallSheetApplication.java   entry point
  Models.java                 users, actors, availability blocks, meetings
  DataStore.java              in-memory data + seed data (swap for a database later)
  ApiController.java          REST API and all access rules
src/main/resources/static/    index.html, app.css, app.js (the UI)
src/test/java/.../AccessRulesTest.java
Dockerfile, render.yaml
```

## Rules enforced on the server

- A manager can only read or change their own actors
- Production houses see BUSY, never shooting/vacation or notes
- Only the side a meeting is waiting on can accept, decline or counter it
- A meeting can be cancelled by either side until it is declined or cancelled

## Moving from demo to production

| Demo | Production |
|---|---|
| Pick-an-account login | Amazon Cognito or Okta, JWT validated by Spring Security, MFA |
| In-memory `DataStore` | PostgreSQL (RDS/Aurora) with Spring Data JPA and Flyway migrations |
| Hosted on Render free tier | ECS Fargate or App Runner behind CloudFront + WAF, Terraform/CDK |
| `.ics` download only | Email via SES, reminders, optional Google/Outlook calendar sync |
| No audit log | Persist the meeting history and availability changes (already modelled) |
| Seed data | Admin onboarding and approval of production houses |
