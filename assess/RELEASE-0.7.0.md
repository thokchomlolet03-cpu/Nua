# Objective-based preparation — 0.7.0

Adds optional local-model assessment of a selected passage against the objective and learner level. Findings contain a requirement, status, exact supporting quote, consequence, remedy and resource-search query. Structural validation rejects invented quotes; semantic judgments still require author review. Failures retain existing material and reviews.

Each finding offers a Google search link, explicitly labelled as unverified search results. Automated retrieval and verification of individual recommended resources is NOT implemented. No document content is automatically sent to a search provider. Users inspect resources externally and approve supplementary text with an HTTPS citation. Supplements are appended as separate sections; original pages remain. The next selected passage must be reassessed if a review exists and the input changed.

Prepared plans snapshot the review, learner level and approved supplements. Existing attempts are not regenerated. Selecting and combining source text remains manual, with a 6,000-character assessed-passage limit. This is not whole-document assessment or automatic PDF rewriting.

Authors may choose 1–20 initial questions with an explicit coverage rationale, then extend to 40. Without a rationale, the legacy 20-type default applies. Initial selection is still the first N registry types; authors must change types and review relevance. Automatic quality-based count and perspective selection remains future work.

Verification: 92 Node tests passed, including quote rejection, supplement source validation and immutable original pages, flexible-plan rationale and legacy compatibility. A live AI review and browser acceptance of this new workflow have not yet been verified. Earlier 0.6.0 documentation is historical.
