# 🚚 CargoMetricsAI

[![Java](https://shields.io)](https://openjdk.org)
[![Spring Boot](https://shields.io)](https://spring.io)
[![Yandex AI Studio](https://shields.io)](https://yandex.cloud)
[![Methodology](https://shields.io)](#specification-driven-development-sdd)

**CargoMetricsAI** is an AI-powered B2B logistics assistant designed for small and medium-sized transport companies, dispatchers, and independent freight drivers. It automates cargo dimensioning, realistic weight estimation, and optimal vehicle matching using just **1–3 photos** of the freight.

By replacing human "eyeballing" with multimodal Vision-Language Models (VLMs), CargoMetricsAI prevents costly empty runs, misallocated vehicles, and cargo/interior damage.

---

## 🎯 Key Features

* **📸 Multimodal Cargo Recognition:** Analyzes 1–3 photos simultaneously to classify cargo type, materials, and condition (new vs. used).
* **⚖️ Physics-Aware Weight & Volume Estimation:** Estimates length, width, height, and total volume (m³). Uses engineered domain prompts to distinguish between solid objects and hollow/frame structures (e.g., empty concrete mixers, barrels, furniture frames) to avoid false weight inflation.
* **🚛 Automated Fleet Matching:** Maps cargo dimensions and mass against a standardized commercial vehicle matrix (e.g., *Standard 3m Gazelle*, *Extended 4.2m/5m Gazelle*, or *Medium-duty 5t trucks*).
* **⚠️ Actionable Logistics Risk Alerts:** Automatically detects:
    * **Fragility:** Glass, mirrors, double-glazed windows (triggers vertical transport & pyramid rack warnings).
    * **Contamination:** Cement dust, oil, mud (triggers protective film/cardboard requirements to save vehicle flooring).
    * **Securing & Balance:** Center-of-gravity shifts, wheels, and fragile external components (motors, handles) that cannot be used as tie-down points.
* **⚡ High-Throughput Architecture:** Built on Java 21 Project Loom (Virtual Threads) to handle concurrent Base64 image encoding and external AI API I/O without blocking platform threads.

---

## 🛠 Tech Stack & Architecture

* **Language & Runtime:** Java 21 (Virtual Threads enabled)
* **Framework:** Spring Boot 4.x (`RestClient` for synchronous I/O on virtual threads)
* **Serialization:** Jackson `ObjectMapper` with custom stack-based JSON bracket balancing to sanitize LLM outputs
* **AI Provider:** Yandex AI Studio via OpenAI-compatible Chat Completions API (`/v1/chat/completions`)
* **Vision Model:** `deepseek-v4.1-flash/latest` (Multimodal `image-text-to-text`)
* **Build Tool:** Apache Maven

### Project Structure
```text
src/main/java/org/cargometricsai/
├── config/          # RestClient & Jackson ObjectMapper configurations
├── controller/      # REST API endpoints (multipart/form-data)
├── domain/dto/      # Java 21 Records for internal & OpenAI-compatible contracts
└── service/         # Multimodal Base64 encoding, prompt orchestration & JSON extraction
specs/               # Specification-Driven Development (SDD) iteration logs
```

---

## 🚀 Getting Started

### Prerequisites
* JDK 21+
* Maven 3.9+
* Yandex Cloud account with an active Folder ID and an API Key (`yc.ai.languageModels.execute` scope)

### Configuration
Set your Yandex Cloud credentials via environment variables or update `src/main/resources/application.yml`:

```bash
export YANDEX_API_TOKEN=your_yandex_api_key
export YANDEX_FOLDER_ID=your_yandex_folder_id
```

### Build & Run
```bash
mvn clean spring-boot:run
```
The service will start on `http://localhost:8080`.

---

## 📡 API Usage

### Analyze Cargo Images
**Endpoint:** `POST /api/v1/cargo/analyze`  
**Content-Type:** `multipart/form-data`

#### Example cURL Request
```bash
curl -X POST http://localhost:8080/api/v1/cargo/analyze \
  -F "images=@/path/to/cargo_photo_1.jpg;type=image/jpeg" \
  -F "images=@/path/to/cargo_photo_2.jpg;type=image/jpeg"
```

#### Example Response (`200 OK`)
```json
{
  "requestId": "9b07193b-648d-40e0-b268-1c211e421cb9",
  "cargoType": "Used gravitational concrete mixer, yellow steel frame and drum, electric motor in black housing, wheels and handle",
  "packaging": "Unpacked, dirty: covered in dried cement mortar, dust, and rust. Requires floor protection.",
  "referenceObjectFound": "Mixer wheels (~0.25-0.3m), brick wall, doorway",
  "singleUnitDimensionsM": {
    "length": 1.3,
    "width": 0.8,
    "height": 1.35
  },
  "totalEstimatedVolumeM3": 1.2,
  "estimatedWeightRangeKg": "from 55 to 85",
  "recommendedVehicle": "Газель Стандарт (3м, до 1.5т)",
  "logisticNotes": "Transport strictly upright. Secure frame with at least 4 ratchet straps; do NOT strap over the motor housing, handle, or gear ring. Chock the wheels and lock the drum from rotating. Place thick cardboard or protective film underneath due to abrasive cement dust."
}
```

---

## 📐 Specification-Driven Development (SDD)

This project strictly follows **SDD** principles. Every architectural decision, prompt calibration, and bug fix is documented as an immutable specification in the `/specs` directory before implementation.

---

## 🗺 Roadmap

- [x] **Iteration 1: Core & AI Integration** — Spring Boot 4 REST API, OpenAI-compatible Yandex AI Studio integration, multimodal Base64 pipeline, and domain prompt engineering.
- [ ] **Iteration 2: Messenger Integration** — Telegram / MAX bot interface for instant photo forwarding by dispatchers and clients (handling media groups and forwarded messages).
- [ ] **Iteration 3: Fleet & Tariff Validation** — Deterministic Java rule engine to cross-check AI outputs against carrier fleet databases and calculate route pricing.
- [ ] **Iteration 4: B2B SaaS & CRM Webhooks** — Multi-tenant management, API keys for transport companies, and CRM webhook integrations.
