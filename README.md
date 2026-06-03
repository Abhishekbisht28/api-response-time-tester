# API Response Time Tester

Automatically tests all your REST API endpoints for response time on every merge/PR.
Sends an HTML email report and **blocks the merge** if any API is slow or returns a wrong status code.

---

## How It Works

```
mvn verify
    │
    ├─ Reads all APIs from apis.json
    ├─ Hits each API and measures response time
    ├─ Checks response time vs threshold (default: 800ms)
    ├─ Checks actual vs expected HTTP status code
    ├─ Sends HTML email report (always)
    │
    ├─ Any API slow or wrong status?
    │       YES → Build FAILS → Merge BLOCKED
    │       NO  → Build PASSES → Merge ALLOWED
```

---

## Quick Start

### 1. Configure `src/test/resources/apis.json`

```json
{
  "baseUrl": "http://localhost:8080",
  "authToken": "Bearer your_token_here",
  "thresholdMs": 800,
  "emailReport": {
    "from": "ci-bot@yourcompany.com",
    "to": "team@yourcompany.com",
    "smtpHost": "smtp.gmail.com",
    "smtpPort": 587,
    "smtpUser": "your@gmail.com",
    "smtpPassword": "your_app_password"
  },
  "apis": [
    {
      "name": "Get All Users",
      "method": "GET",
      "endpoint": "/api/users",
      "headers": { "Content-Type": "application/json" },
      "body": null,
      "expectedStatusCode": 200
    },
    {
      "name": "Create User",
      "method": "POST",
      "endpoint": "/api/users",
      "headers": { "Content-Type": "application/json" },
      "body": { "name": "John", "email": "john@example.com" },
      "expectedStatusCode": 201
    }
  ]
}
```

### 2. Run Tests

```bash
mvn verify
```

That's it! Results are printed to console and emailed automatically.

---

## Project Structure

```
api-response-time-tester/
├── pom.xml                              ← Maven dependencies & plugins
├── .github/workflows/api-test.yml       ← GitHub Actions CI (blocks PR merge)
├── .gitlab-ci.yml                       ← GitLab CI (blocks merge request)
└── src/
    └── test/
        ├── java/
        │   ├── ApiConfig.java           ← Loads and parses apis.json
        │   ├── ApiResponseTimeIT.java   ← Main test class
        │   └── EmailReporter.java       ← Builds and sends HTML email
        └── resources/
            └── apis.json               ← ✏ EDIT THIS FILE to add/remove APIs
```

---

## Adding New APIs

Just add an entry to `apis.json` — no Java code changes needed:

```json
{
  "name": "My New API",
  "method": "GET",
  "endpoint": "/api/my-new-endpoint",
  "headers": { "Content-Type": "application/json" },
  "body": null,
  "expectedStatusCode": 200
}
```

---

## Supported HTTP Methods

`GET` · `POST` · `PUT` · `PATCH` · `DELETE`

---

## Email Report

The HTML email includes:
- Overall status (PASSED / FAILED)
- Summary stats (total, passed, failed, slow, wrong status)
- Per-API table with response time, status codes, and result tags

---

## CI Integration

### GitHub Actions
The workflow in `.github/workflows/api-test.yml` automatically:
- Runs on every Pull Request to `main`
- Blocks the merge if `mvn verify` fails

### GitLab CI
The `.gitlab-ci.yml` runs on every Merge Request and blocks it on failure.

---

## Configuration Reference

| Field | Description |
|---|---|
| `baseUrl` | Base URL of your API server |
| `authToken` | Bearer token added to every request |
| `thresholdMs` | Max allowed response time in milliseconds |
| `emailReport.from` | Sender email address |
| `emailReport.to` | Recipient email address |
| `emailReport.smtpHost` | SMTP server host |
| `emailReport.smtpPort` | SMTP server port (587 for TLS) |
| `emailReport.smtpUser` | SMTP username |
| `emailReport.smtpPassword` | SMTP password or app password |

---

## Gmail Setup

If using Gmail as SMTP:
1. Enable 2-Factor Authentication on your Google account
2. Go to Google Account → Security → App Passwords
3. Generate an app password and use it as `smtpPassword`

---

## Requirements

- Java 17+
- Maven 3.6+


## Changed-only API testing

This version can test only APIs impacted by the latest PR/push.

`src/test/resources/apis.json`:

```json
{
  "baseUrl": "http://127.0.0.1:9000/api",
  "thresholdMs": 1500,
  "scanConfig": {
    "projectPath": "../",
    "urlsFile": "store/urls.py",
    "changedOnly": true,
    "baseBranch": "origin/master"
  }
}
```

How it works:

1. Runs `git diff origin/master...HEAD` inside the Django project.
2. Finds changed `.py` files.
3. Detects changed view functions/classes.
4. Maps those symbols to Django routes discovered from `store/urls.py`.
5. Tests only impacted APIs.
6. Generates `target/ExtentReport.html`.

For local Windows testing, copy `apis-local.json` to:

```text
src/test/resources/apis.json
```

For GitHub Actions, keep `apis-github.json` or the default `src/test/resources/apis.json`.

