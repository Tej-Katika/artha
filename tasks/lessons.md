# Lessons Learned

Patterns captured after corrections. Reviewed at the start of each session.

---

## Tool Architecture

**Lesson:** Always check whether a tool implements `FinancialTool` AND has `@FinWiseTool` before assuming it is reachable by the agent.
- `@FinWiseTool` alone triggers `ToolRegistry` auto-discovery
- Missing either annotation means the tool is silently unreachable
- The `AgentOrchestrator` does NOT have a fallback — unknown tools return `{"error":"Unknown tool: ..."}`

---

## Legacy Tool Migration

**Lesson:** Legacy tools with `execute(JsonNode, String): String` cannot directly implement `FinancialTool` because the interface's default method has the same signature but returns `ToolResult` (incompatible return type = compile error).
- Rename the internal method to `executeInternal` and add the proper `execute(JsonNode, ToolContext): ToolResult` override
- Wrap the JSON string result via `objectMapper.readTree(json)` → `ToolResult.ok(data)`

---

## ToolResult Contract

**Lesson:** `ToolResult.data()` is serialized by `AgentOrchestrator` via `objectMapper.writeValueAsString(result.data())`. Returning a pre-serialized JSON string as `data` causes double-serialization (the string gets JSON-escaped as a string literal).
- Always return `Map`, `List`, or `JsonNode` — never a `String` containing JSON

---

## Always Read application.yml Before Trusting CLAUDE.md Config

**Lesson:** The CLAUDE.md had wrong values for DB name (`finwise` → actual: `postgres`), `ddl-auto` (`validate` → actual: `none`), and default model (`claude-haiku-4-5-20251001` → actual: `claude-sonnet-4-5-20250929`). CLAUDE.md drifts from code.
- Always read `application.yml` directly when DB connection or config matters
- Trust the file over the documentation

---

## Verify Generator Scripts Before Running — UUID Prefix Bug

**Lesson:** The v2 data generator had a bug where ALL archetypes used `"dd"` as UUID prefix (`ARCH_PREFIX[arch_name] = "dd" + str(i).zfill(6)`). This silently created 501 users all with `dd*` UUIDs, making archetype-based queries wrong and the `--clear` flag miss old data.
- Always do a dry-run / test print of key outputs (like UUIDs) before running insert scripts
- The fix: hardcode `ARCH_PREFIX` as a dict with the documented per-archetype hex prefixes

---

## Enrichments Are Not Auto-Populated on Data Insert

**Lesson:** Inserting 500 synthetic profiles via `generate_finwise_data_v2.py` does NOT populate `transaction_enrichments`. That table requires the Spring Boot backend to be running and `POST /api/enrichment/user/{userId}/all` to be called per user. Without enrichment, `get_anomalies` and `get_category_insights` return empty results.
- After any data insert, trigger enrichment before testing anomaly/category tools
- Core tools (spending summary, budget, transactions) work without enrichments since category lives in `transactions.metadata` JSONB

---

<!-- Add new lessons below as corrections occur -->
