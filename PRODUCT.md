# VisiDock

## Register

product

## Platform

android

## Users and purpose

An Android visiting-card manager for people who collect cards and need to remember who they met. Photograph or import a card, review extracted details, retain the image, add context, find the person later and explicitly add them to phone contacts when needed.

## Identity

VisiDock. Android application ID and namespace: `com.thotapalli.visidock`, chosen by the owner. The disposable demo uses `.demo` as an application ID suffix.

## Experience

The owner wants a modern, slick, polished interface with high-quality motion that feels impressive. No existing visual reference or fixed palette was supplied. The current green direction is an implementation choice open to visual iteration.

## Principles

- Remember the person and meeting context, not only the text on the card.
- Review before saving. OCR and semantic results can be wrong.
- Semantic search stays on-device, explicitly requested by the owner.
- Contact export is always an explicit user action.
- Reliability, privacy and device responsiveness must support the visual experience.

## Accessibility

Implementation baseline: native Material touch targets, scalable text, light/dark modes, system Back, window/keyboard insets and system animation settings. These are engineering requirements, not a claim of completed accessibility certification.

## Ownership

Firebase project visidock-thotapalli and email/password authentication are provisioned. Standard Firestore and its rules are deployed in Mumbai (asia-south1). The owner selected private Cloudflare R2 for images; R2 is deployed with private access, and the live API lifecycle/isolation checks pass. Physical-device and release verification remain pending. The owner controls hosting/service ownership, release signing and Google Play testing. Do not describe a locally built demo as a production-connected app.
