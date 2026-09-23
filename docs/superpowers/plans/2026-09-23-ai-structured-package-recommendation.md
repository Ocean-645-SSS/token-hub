# AI Structured Package Recommendation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert a natural-language package requirement into a validated structured query before Elasticsearch retrieval, then generate a recommendation grounded exclusively in the retrieved packages.

**Architecture:** `AiGuideService` will call the existing OpenAI-compatible `ModelProvider` first with a JSON-only extraction prompt. The service will parse a fixed criteria record, normalize and validate each field against server-owned limits, retrieve candidates through `PackageSearchService`, and make a second grounded recommendation call. Invalid or unusable LLM JSON falls back to a keyword-only query, so the API remains useful and LLM output never becomes executable query text.

**Tech Stack:** Java 17, Spring Boot 3, Jackson, Elasticsearch REST API, JUnit 5 and Mockito.

## Global Constraints

- Keep the single OpenAI-compatible `ModelProvider`; configure Aliyun through environment variables only.
- Never add a real API key to version control; `.env` is ignored and contains placeholders only.
- The LLM can emit only the documented fields, and the backend owns validation, defaults, bounds, and Elasticsearch query construction.
- Recommendation text may mention only candidates returned by Elasticsearch.

---

### Task 1: Add a typed, bounded structured-query contract

**Files:**
- Create: `src/main/java/com/tokenhub/dto/PackageSearchCriteria.java`
- Modify: `src/main/java/com/tokenhub/config/ModelProperties.java`
- Test: `src/test/java/com/tokenhub/service/AiGuideServiceTest.java`

**Interfaces:**
- Produces `PackageSearchCriteria(String keyword, String modelCode, BigDecimal minPrice, BigDecimal maxPrice, Long minTokens, Long maxTokens)`.
- Produces `ModelProperties#getGuideModel()` for both LLM calls.

- [ ] **Step 1: Write failing normalization tests**

```java
assertEquals("coding assistant", result.criteria().keyword());
assertNull(result.criteria().maxPrice());
```

- [ ] **Step 2: Implement the record and configuration property**

```java
public record PackageSearchCriteria(String keyword, String modelCode, BigDecimal minPrice,
        BigDecimal maxPrice, Long minTokens, Long maxTokens) { }
```

```java
private String guideModel = "gpt-4o-mini";
```

- [ ] **Step 3: Run the focused tests**

Run: `mvn -Dtest=AiGuideServiceTest test`

Expected: the new test compiles and passes.

### Task 2: Parse, normalize, and validate LLM query JSON

**Files:**
- Modify: `src/main/java/com/tokenhub/service/AiGuideService.java`
- Test: `src/test/java/com/tokenhub/service/AiGuideServiceTest.java`

**Interfaces:**
- Consumes the JSON content returned by `ModelProvider.complete(ChatCompletionRequest)`.
- Produces `PackageSearchCriteria` with only known fields and valid ranges.
- Falls back to `new PackageSearchCriteria(requirement, null, null, budget, null, null)` on malformed JSON, unknown model code, invalid numeric bounds, or unavailable parser response.

- [ ] **Step 1: Write failing tests for valid fenced JSON and malformed JSON fallback**

```java
when(provider.complete(any())).thenReturn(response("{\"keyword\":\"code assistant\",\"maxPrice\":88}"));
verify(packages).search(eq("code assistant"), isNull(), isNull(), eq(new BigDecimal("88")), isNull(), isNull(), eq(1), eq(20));
```

```java
when(provider.complete(any())).thenReturn(response("not json"));
verify(packages).search(eq("original requirement"), isNull(), isNull(), eq(new BigDecimal("100")), isNull(), isNull(), eq(1), eq(20));
```

- [ ] **Step 2: Implement a JSON-only extraction prompt and server-side normalization**

```java
private PackageSearchCriteria extractCriteria(AiRecommendRequest request) {
    // Call guideModel with a fixed schema instruction, parse only JSON object fields,
    // and return the deterministic fallback if parsing or validation fails.
}
```

- [ ] **Step 3: Run the focused tests**

Run: `mvn -Dtest=AiGuideServiceTest test`

Expected: valid JSON maps to filters; malformed JSON cannot alter filters.

### Task 3: Connect structured criteria to ES and grounded recommendation

**Files:**
- Modify: `src/main/java/com/tokenhub/service/PackageSearchService.java`
- Modify: `src/main/java/com/tokenhub/service/AiGuideService.java`
- Modify: `src/main/java/com/tokenhub/dto/AiRecommendResponse.java`
- Test: `src/test/java/com/tokenhub/service/PackageSearchServiceTest.java`
- Test: `src/test/java/com/tokenhub/service/AiGuideServiceTest.java`

**Interfaces:**
- `PackageSearchService.searchPackages(PackageSearchCriteria criteria)` delegates all six criteria fields to `search`.
- `AiRecommendResponse` returns `criteria` alongside `candidates`, assumptions, and grounded recommendation.

- [ ] **Step 1: Write the failing candidate-lookup and response tests**

```java
verify(packages).search("coding", "gpt-4o-mini", null, new BigDecimal("100"), 1_000L, null, 1, 20);
assertEquals("coding", result.criteria().keyword());
```

- [ ] **Step 2: Implement ES delegation and a second candidate-only recommendation call**

```java
public List<TokenPackage> searchPackages(PackageSearchCriteria criteria) {
    return search(criteria.keyword(), criteria.modelCode(), criteria.minPrice(), criteria.maxPrice(),
            criteria.minTokens(), criteria.maxTokens(), 1, 20).records();
}
```

- [ ] **Step 3: Run focused tests**

Run: `mvn -Dtest=PackageSearchServiceTest,AiGuideServiceTest test`

Expected: structured values reach ES and recommendation retains candidate-only grounding.

### Task 4: Configure and document the Aliyun-compatible provider

**Files:**
- Create: `.env`
- Modify: `.gitignore`
- Modify: `src/main/resources/application.yaml`
- Modify: `README.md`

**Interfaces:**
- `.env` contains only `TOKENHUB_MODEL_BASE_URL`, `TOKENHUB_MODEL_API_KEY`, and `TOKENHUB_MODEL_GUIDE_MODEL` placeholders.
- `application.yaml` maps `tokenhub.model.guide-model` to `TOKENHUB_MODEL_GUIDE_MODEL`.

- [ ] **Step 1: Add ignored environment template with no credential**

```dotenv
TOKENHUB_MODEL_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
TOKENHUB_MODEL_API_KEY=
TOKENHUB_MODEL_GUIDE_MODEL=qwen-plus
```

- [ ] **Step 2: Document the two-stage guarantee and local configuration**

```text
Natural language -> schema-constrained JSON -> backend validation -> ES candidates -> grounded explanation
```

- [ ] **Step 3: Verify the template is ignored**

Run: `git check-ignore -v .env`

Expected: `.gitignore` is reported.

### Task 5: Full verification and delivery

**Files:**
- Modify: all files from Tasks 1-4 only as required by test failures.

- [ ] **Step 1: Run the complete test suite**

Run: `mvn test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Review the complete diff and repository status**

Run: `git diff --check` and `git status --short`

Expected: no whitespace errors; only intended files staged for this feature.

- [ ] **Step 3: Commit and push only the feature files**

```bash
git add .gitignore README.md src/main/java/com/tokenhub/config/ModelProperties.java src/main/java/com/tokenhub/dto/PackageSearchCriteria.java src/main/java/com/tokenhub/dto/AiRecommendResponse.java src/main/java/com/tokenhub/service/AiGuideService.java src/main/java/com/tokenhub/service/PackageSearchService.java src/main/resources/application.yaml src/test/java/com/tokenhub/service/AiGuideServiceTest.java src/test/java/com/tokenhub/service/PackageSearchServiceTest.java docs/superpowers/plans/2026-09-23-ai-structured-package-recommendation.md
git commit -m "feat: add structured AI package recommendation"
git push
```

## Self-Review

- Spec coverage: Tasks 1-3 cover extraction, backend validation, ES filtering, candidate grounding, and tests. Task 4 covers Aliyun configuration without secrets. Task 5 validates and publishes the implementation.
- Placeholder scan: no implementation task relies on unspecified error handling or test coverage.
- Type consistency: `PackageSearchCriteria` is the only structured-query type passed from `AiGuideService` to `PackageSearchService`; `guideModel` is configured under `tokenhub.model`.
