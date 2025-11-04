# 🚀 GoPDFGenie — HTML → PDF/PNG API

Convert **real web pages** or **HTML bundles** into pixel‑perfect **PDF** or **PNG** — reliably, asynchronously, and developer‑friendly.

[![Made for Developers](https://img.shields.io/badge/made_for-developers-blueviolet)](#)
[![Async Jobs](https://img.shields.io/badge/async-jobs-success)](#)
[![Formats](https://img.shields.io/badge/output-PDF%20%7C%20PNG-informational)](#)
[![License](https://img.shields.io/badge/license-Apache--2.0-lightgrey)](#license)

**Base URL:** `https://gopdfgenie.com/api/v1` • **Docs:** https://gopdfgenie.com/swagger-ui/index.html

---

## ✨ Why GoPDFGenie?
- **Real pages, real results** — Works with dashboards, long reports, and complex layouts.
- **Two easy modes** — Convert a **public URL** or **upload** your HTML/ZIP.
- **Async that feels instant** — Create a job → poll → download. No long‑running HTTP connections.
- **Simple knobs** — `orientation`, `outputFormat`, `pageSize`, `quality`.

> This README matches the controllers in your backend (PdfConversionController & JobController).

---

## ⚡ Quick Start (30 seconds)

```bash
# 1) Create a job (URL → PDF)
curl -X POST "https://gopdfgenie.com/api/v1/convert/url/async?orientation=portrait&outputFormat=pdf&pageSize=A4&quality=STANDARD"   -H "Authorization: Bearer $GOPDFGENIE_API_KEY"   -H "Content-Type: application/json"   -d '{ "url": "https://example.com/dashboard" }'

# ⇣⇣ response
# { "jobId": "uuid" }

# 2) Poll status
curl -H "Authorization: Bearer $GOPDFGENIE_API_KEY"   "https://gopdfgenie.com/api/v1/jobs/<jobId>/status"

# 3) Download result (when COMPLETED)
curl -L -H "Authorization: Bearer $GOPDFGENIE_API_KEY"   "https://gopdfgenie.com/api/v1/jobs/<jobId>/result" -o output.pdf
```

---

## 🧭 API at a Glance

| Method | Path                                  | Content‑Type          | Purpose                                   |
|------: |-------------------------------------- |---------------------- |-------------------------------------------|
| POST   | `/convert/url/async`                  | `application/json`    | Convert a **public URL** to PDF/PNG       |
| POST   | `/convert/async`                      | `multipart/form-data` | Convert an **uploaded HTML/ZIP**          |
| GET    | `/jobs/{jobId}/status`                | —                     | Check job status                          |
| GET    | `/jobs/{jobId}/result`                | —                     | **Stream** the finished file (PDF/PNG)    |
| GET    | `/jobs/{jobId}/download`              | —                     | Get a JSON link to `/result` (if ready)   |

**Auth:** Every request must include  
```
Authorization: Bearer YOUR_API_KEY
```

> Using RapidAPI with a proxy secret? Add: `X-RapidAPI-Proxy-Secret: <your-secret>`

---

## 🧩 Convert a Public URL

**Endpoint**: `POST /convert/url/async`  
**Content‑Type**: `application/json`

**Query Parameters**
- `orientation`: `portrait` (default) \| `landscape`
- `outputFormat`: `pdf` (default) \| `png`
- `pageSize`: `Long` (default) \| `A4` \| `A5` \| `Letter` \| `Legal` \| `Tabloid`
- `quality`: `STANDARD` (default) \| `LOW` \| `MEDIUM` \| `HIGH`

**Body**
```json
{ "url": "https://example.com/report" }
```

**Example**
```bash
curl -X POST "https://gopdfgenie.com/api/v1/convert/url/async?orientation=landscape&outputFormat=pdf&pageSize=Letter&quality=STANDARD"   -H "Authorization: Bearer $GOPDFGENIE_API_KEY"   -H "Content-Type: application/json"   -d '{ "url": "https://example.com/report" }'
# => { "jobId": "uuid" }
```

---

## 📦 Convert an Upload (HTML or ZIP)

**Endpoint**: `POST /convert/async`  
**Content‑Type**: `multipart/form-data`

**Form fields**
- `file` — your `index.html` or a `.zip` containing HTML + assets

**Query Parameters** (same as above)
- `orientation`, `outputFormat`, `pageSize`, `quality`

**Example**
```bash
curl -X POST "https://gopdfgenie.com/api/v1/convert/async?orientation=portrait&outputFormat=png&pageSize=Long&quality=HIGH"   -H "Authorization: Bearer $GOPDFGENIE_API_KEY"   -F "file=@site.zip;type=application/zip"
# => { "jobId": "uuid" }
```

> **Tip:** For a single tall **PNG**, set `outputFormat=png` and `pageSize=Long`.

---

## 🔁 Track & Fetch

### Check status
`GET /jobs/{jobId}/status` → returns:
```json
{ "status": "PENDING" }
```
```json
{ "status": "RUNNING" }
```
```json
{
  "status": "COMPLETED",
  "requestedDpi": 200,
  "actualDpi": 200,
  "purgeAt": "2025-12-01T10:20:30Z"
}
```
```json
{ "status": "FAILED" }
```

### Download the result
- **Stream the file:** `GET /jobs/{jobId}/result`  
  - Returns `application/pdf` or `image/png` with a proper filename.  
  - If still processing: **202 Accepted** + `{ "message": "Job is still processing." }`

- **Or get a link first:** `GET /jobs/{jobId}/download` →  
  `{ "downloadUrl": "/api/v1/jobs/<jobId>/result" }` (only when **COMPLETED**)

---

## 🎛️ Options Cheat‑Sheet

| Option          | Values                                        | Best for                              |
|-----------------|-----------------------------------------------|---------------------------------------|
| `orientation`   | `portrait` \| `landscape`                     | PDF page layout                       |
| `outputFormat`  | `pdf` \| `png`                                | Choose document vs image              |
| `pageSize`      | `Long`, `A4`, `A5`, `Letter`, `Legal`, `Tabloid` | `Long` for tall PNG; `A4/Letter` for PDF |
| `quality`       | `STANDARD`, `LOW`, `MEDIUM`, `HIGH`           | PNG DPI preset                        |

---

## 💡 Examples to Copy‑Paste

**Node (fetch)**
```js
const API = "https://gopdfgenie.com/api/v1";
const H = { "Authorization": `Bearer ${process.env.GOPDFGENIE_API_KEY}`, "Content-Type": "application/json" };

const submit = await fetch(`${API}/convert/url/async?orientation=portrait&outputFormat=pdf&pageSize=A4&quality=STANDARD`, {
  method: "POST",
  headers: H,
  body: JSON.stringify({ url: "https://example.com/dashboard" })
});
const { jobId } = await submit.json();

let status, link;
for (let i = 0; i < 60; i++) {
  await new Promise(r => setTimeout(r, 2000));
  const r = await fetch(`${API}/jobs/${jobId}/status`, { headers: H });
  const j = await r.json();
  status = j.status;
  if (status === "COMPLETED") { link = `${API}/jobs/${jobId}/result`; break; }
  if (status === "FAILED") throw new Error("Conversion failed");
}
// download using link (stream to file)
```

**Python (requests)**
```python
import os, time, json, requests, pathlib

API = "https://gopdfgenie.com/api/v1"
H = {"Authorization": f"Bearer {os.environ['GOPDFGENIE_API_KEY']}", "Content-Type": "application/json"}

# submit
r = requests.post(f"{API}/convert/url/async?orientation=portrait&outputFormat=pdf&pageSize=A4&quality=STANDARD",
                  headers=H, data=json.dumps({"url":"https://example.com/report"}))
jobId = r.json()["jobId"]

# poll
for _ in range(60):
    time.sleep(2)
    j = requests.get(f"{API}/jobs/{jobId}/status", headers=H).json()
    if j["status"] == "COMPLETED":
        b = requests.get(f"{API}/jobs/{jobId}/result", headers=H).content
        pathlib.Path("output.pdf").write_bytes(b)
        break
    if j["status"] == "FAILED":
        raise SystemExit("Conversion failed")
```

---

## 🔐 Authentication

Every request must include your API key:
```
Authorization: Bearer YOUR_API_KEY
```
RapidAPI (if configured):  
```
X-RapidAPI-Proxy-Secret: <your-secret>
```

---

## 🧰 Errors (summary)

- `400` — bad input (missing URL/file, invalid query values)  
- `401` — missing/invalid token  
- `403` — accessing someone else’s job  
- `404` — job not found (or not yours)  
- `413` — payload too large (plan limit)  
- `429` — rate‑limited or out of credits  
- `5xx` — transient server issue

---

## 💸 Pricing & Credits

- Usage: **1 credit per 10 MB of output** (rounded up)  
- Plans: Free, Starter, Pro, Business; on‑prem/enterprise available  
- Details: https://gopdfgenie.com/pricing

---

## 📜 License

Examples in this repository are provided under **Apache‑2.0**. See `LICENSE`.
