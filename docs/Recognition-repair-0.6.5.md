# Extraction repair data retention

## Reproduced failures

The first reading could contain a phone but omit the name. A corrective reading could recover the name while omitting the phone. Version 0.6.4 rejected that entire correction, discarding the recovered name. A focused JVM regression demonstrated the old result before integration of the fix.

The whole-response acceptance path also allowed values with first-pass review issues to disappear when a second reading had fewer issues. An issue count is not a measure of field retention or person identity.

## Correction contract

A second reading must retain previously read data. Independently supported missing fields can be recovered without accepting unrelated omissions. People must be matched unambiguously, never merely by their array position; ambiguous ownership remains unresolved. Source annotations continue to indicate where to review a value, not a guarantee that model classification is correct.

The production failure path no longer substitutes a basic regex contact and announces a successful visual read after an installed visual model fails. It retains photographs, OCR and existing drafts and returns to retry or explicit manual entry.

Opt-in private diagnostics include numeric field-presence masks for initial output, corrective output, accepted output and form output. The bits represent name, role, company, address, phones, emails and websites. Values, photographs, source text and contact identifiers are not recorded. Diagnostics remain local and off by default.

## Evidence limits

Two Windows CPU runs with the pinned model, production instruction and synthetic image/OCR input filled the expected split-company and two-person fixtures. Neither naturally required repair. They establish a baseline, not Android accuracy or coverage of the reported private card. JVM tests separately exercise the production parser/repair/form handoff and adversarial ownership cases. The native failure-handoff regression requires a connected Android device to execute; compilation alone does not count as execution.
