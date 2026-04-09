# Integration Notes

## Simple Syllabus (MGA)

- Start with public syllabus library pages for low-friction onboarding.
- Parse syllabus pages into normalized `CourseArtifact` records:
  - assignment, exam, reading, milestone
  - title, due date, weight, notes, source URL

## D2L / Brightspace

### Preferred path: Official APIs

1. Request OAuth2 client from campus D2L admin.
2. Implement OAuth authorize + callback flow.
3. Pull assignments, quizzes, grades, and calendar events via official endpoints.

### Fallback path: Browser-session import

- User logs into D2L in embedded/browser flow.
- Capture minimal session metadata, then import visible deadlines.
- Encrypt and rotate stored session tokens.
- Provide one-click disconnect and data purge.

## Calendar sync

- In-app calendar is source of truth.
- Add outbound sync adapters:
  - Google Calendar OAuth + event upsert
  - Microsoft Graph Calendar OAuth + event upsert
