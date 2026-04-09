# AI Academic Planner

Multi-user planning app that turns course syllabi into a weekly gameplan.

## Hackathon MVP

- Extract assignments, exams, and deadlines from syllabus content
- Generate weekly study plans
- Provide an AI assistant for "what should I work on today?"
- In-app calendar with export/sync hooks for Google and Outlook

## Stack (Starter)

- Backend: Java 21 + Spring Boot
- Frontend: React + Vite (to be scaffolded next)
- Database: PostgreSQL (for users, courses, events, plans)
- Auth: Email/password now, SSO-ready design

## Data Connectors

- **Simple Syllabus**: public content ingestion from MGA library pages
- **D2L/Brightspace**:
  - Preferred: official API + OAuth client from school admin
  - Fallback: browser-session import flow for personal account data

## D2L API Access (How to Request)

To get proper API access at your institution, ask your D2L admin for:

1. Brightspace OAuth2 client registration for your app
2. API scopes for courses, grades, content, calendar, assignments
3. Developer key/client ID + client secret
4. Redirect URI allowlist for your app environments
5. Rate limits and acceptable use guidance

## Repo Layout

- `backend/` Spring Boot API
- `frontend/` React app for import + plan dashboard
- `docs/` architecture and integration notes
- `docker-compose.yml` local PostgreSQL

## Local Run

```bash
docker compose up -d

cd backend
mvn spring-boot:run

cd ../frontend
npm install
npm run dev
```

Backend: `http://localhost:8080/api/health`  
Frontend: `http://localhost:5173`

## Demo Flow Endpoints

- `GET /api/integrations/import?userId=demo-student`
- `GET /api/plans/weekly?userId=demo-student`
- `GET /api/assistant/today?userId=demo-student`

## Notes

- D2L connector currently ships as a stub with API-first design and browser-session fallback path.
- Google/Outlook sync are scaffolded as capability flags in the weekly plan payload.
