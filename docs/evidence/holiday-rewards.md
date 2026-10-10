# Holiday rewards — SPEAR

Base: wsg138/EnthusiaTags main 28048ca. Scope: source PR, local verification and interactive preview; no server activation.

Spec: REQ-917..919. Existing root menu stops after seven categories, so merely appending Holidays would hide it. Canonical configuration lacks holiday tag definitions. SMP Test's Pumpkin Hunter uses an orange gradient without bold; custom Pumpkin King crown styling must be retained.

Prove: migration and category tests cover preserving custom fields, bold styling, parent validation, configured catalog entries, and root/child pagination. Catalog entries display ownership only; EnthusiaHolidays owns event awards.

Engine/arch/refine: after integrating the existing PR #29 holiday catalog and crown assets, 258 Maven tests and shaded artifact checks pass; EARS and 12 Node tooling tests pass. HolidayMenuTest checks visible root categories, parent navigation, catalog ownership with no claims, and bounded pagination using Bukkit/item mocks. Preserve reward IDs, claim semantics, permissions and existing tag ownership. Native client acceptance remains pending.

Preview: https://enthusia-holiday-rewards-preview.awareyak.chatgpt.site (private, requires owner ChatGPT login). Local browser QA at 390px verified no horizontal document overflow, Halloween/Christmas navigation and earned/locked states. The IAB's published-site login encountered security verification; authenticated mobile access is not independently confirmed. Local Sites archive packaging is unavailable because its Bash dependency is absent; exact pushed source was published using the supported remote-build fallback.

## Corrected production menu extension (REQ-920, REQ-921)
Production reference: SMP41f458f0 EnthusiaTags-2.2.2-unique-mail-test.1.jar downloaded read-only on2026-10-10. javap confirms RewardMenuState dashboard/browser and associated items/actions; source7f6eb62 establishes45-slot black/orange dashboard,7 categories per page,54-slot browser with21 reward positions. Current-main recovery branch be944de retains this presentation and supplies verified progress/trusted slot actions.

The rejected bare inventory replacement is superseded. Integrated existing browser recovery source into the holiday branch, preserving dashboard/browser frames and controls. Holidays uses root-category pages, seasonal children use existing browser item positions, Back returns to parent, and catalog entries never get CLAIM actions. Existing reward definitions, filters, sort, progress and delivery stay under RewardService. Bold Pumpkin Hunter and custom crown catalog remain.

Proof: new production-layout HolidayMenuTest failed compilation for missing parent navigation before implementation. An ordering assertion then exposed alphabetic seasonal order; children now retain configured order. Java25 clean verify passes269 tests (no failures/errors/skips), shaded SQLite probe, EARS and12 Node tooling tests. Tests assert45/54 inventory sizes, original Tags/Cosmetics/Ready/Close positions, root pagination excludes seasonal children, nested Back and earned/locked catalog without claim calls.

TEST rollback: old supporter-test10 artifact restored, redesigned JAR renamed .rejected-menu.disabled; pre-category rewards config restored and TEST restarted. Version response13:06:27 confirms supporter-test10. Production untouched.

Interactive preview now models production frames/category pages/browser controls; item symbols and existing reward rows are simulated. Native client acceptance remains pending. Existing rewards files require category entries to be installed explicitly; deployment uses a minimal category edit, preserving all payouts and criteria.
