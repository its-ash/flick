# store.mk: the Ash Store release kit, shared by every its-ash Android app.
#
# Canonical copy: ash-store/store-kit/store.mk. Do not edit the copy in an
# app repo. Edit the canonical copy, then run `make store-sync` at the
# workspace root to copy it into every app.
#
# The app Makefile sets these before `include store.mk`:
#   STORE_ID      slug from store.json. The release asset is $(STORE_ID).apk.
#   APP_DIR       folder with pubspec.yaml. Default: .
#   APK           APK made by `make build`. Default: Flutter's release APK.
#   EXTRA_ASSETS  more files to attach to the release. Optional.
#   RELEASE_DEPS  targets to run after the build, before the release. Optional.
# and defines a `build` target that builds $(APK).
#
# Targets:
#   make deploy       check, bump version, build, commit, tag, push, GitHub release
#   make release      commit, tag, push and publish the APK already built
#   make bump         patch +1 and build number +1 in pubspec.yaml
#   make store-check  check store.json, landing page, gh auth and branch
#   make store-meta   set the repo topic, homepage and description; turn on Pages
#   make version      print the pubspec version

SHELL := /bin/bash

APP_DIR ?= .
APK ?= $(APP_DIR)/build/app/outputs/flutter-apk/app-release.apk
DIST := dist
PUBSPEC := $(APP_DIR)/pubspec.yaml
STORE_TOPIC := ash-store

json = $(shell sed -n 's/^ *"$(1)": *"\([^"]*\)".*/\1/p' store.json | head -1)
VERSION = $(shell sed -n 's/^version: *\([0-9][0-9.]*\).*/\1/p' $(PUBSPEC))
TAG = v$(VERSION)
STORE_NAME = $(call json,name)
STORE_REPO = $(call json,repo)
STORE_HOME = $(call json,homepage)
STORE_TAGLINE = $(call json,tagline)

.PHONY: deploy release bump store-check store-meta version

deploy: store-check
	@$(MAKE) --no-print-directory bump
	@$(MAKE) --no-print-directory build
	@$(MAKE) --no-print-directory release

version:
	@echo $(VERSION)

bump:
	@cur=$$(sed -n 's/^version: *//p' $(PUBSPEC)); name=$${cur%%+*}; build=$${cur#*+}; \
	[ "$$build" = "$$cur" ] && build=0; \
	IFS=. read -r ma mi pa <<< "$$name"; next="$$ma.$$mi.$$((pa + 1))+$$((build + 1))"; \
	perl -pi -e "s/^version: .*/version: $$next/" $(PUBSPEC); \
	echo "== version $$cur -> $$next"

store-check:
	@test -f store.json || { echo "store.json is missing. See ash-store/store-kit/STORE.md."; exit 1; }
	@[ "$(call json,id)" = "$(STORE_ID)" ] || { echo "store.json id '$(call json,id)' != STORE_ID '$(STORE_ID)'"; exit 1; }
	@[ -n "$(STORE_REPO)" ] || { echo "store.json needs \"repo\": \"owner/name\""; exit 1; }
	@test -f index.html || echo "warning: no index.html landing page at the repo root"
	@test -f CNAME || echo "warning: no CNAME; the landing page will use the github.io URL"
	@gh auth status >/dev/null 2>&1 || { echo "Run: gh auth login"; exit 1; }
	@[ "$$(git symbolic-ref --short HEAD)" = "main" ] || { echo "Deploy from main (now on $$(git symbolic-ref --short HEAD))."; exit 1; }
	@echo "== store-check ok: $(STORE_NAME) ($(STORE_REPO))"

release: $(RELEASE_DEPS)
	@test -f "$(APK)" || { echo "No APK at $(APK). Run make build."; exit 1; }
	@! git rev-parse -q --verify "refs/tags/$(TAG)" >/dev/null || { echo "Tag $(TAG) exists. Run make bump first."; exit 1; }
	@mkdir -p $(DIST)
	cp "$(APK)" $(DIST)/$(STORE_ID).apk
	cd $(DIST) && shasum -a 256 $(STORE_ID).apk > $(STORE_ID).apk.sha256
	git add -A
	@msg="release: $(TAG)"; \
	if command -v copilot >/dev/null 2>&1; then \
		m=$$(copilot -sp 'Analyze the staged git changes and generate a concise commit message. Output ONLY the commit message. Do not execute any commands. Do not include quotes, markdown, explanation, or bullet points.' 2>/dev/null) \
		&& [ -n "$$m" ] && msg="$$m ($(TAG))"; \
	fi; \
	git commit -m "$$msg" || echo "== nothing to commit"
	git tag -a $(TAG) -m "$(STORE_NAME) $(TAG)"
	git push origin HEAD
	git push origin $(TAG)
	gh release create $(TAG) $(DIST)/$(STORE_ID).apk $(DIST)/$(STORE_ID).apk.sha256 $(EXTRA_ASSETS) \
		--repo $(STORE_REPO) --title "$(STORE_NAME) $(TAG)" --generate-notes --latest
	@$(MAKE) --no-print-directory store-meta
	@echo "== released $(STORE_NAME) $(TAG): https://github.com/$(STORE_REPO)/releases/tag/$(TAG)"

store-meta:
	gh repo edit $(STORE_REPO) --add-topic $(STORE_TOPIC) --add-topic android --add-topic flutter \
		--homepage "$(STORE_HOME)" --description "$(STORE_TAGLINE)"
	@gh api repos/$(STORE_REPO)/pages >/dev/null 2>&1 \
		|| gh api -X POST repos/$(STORE_REPO)/pages -f 'source[branch]=main' -f 'source[path]=/' >/dev/null \
		|| echo "warning: could not turn on GitHub Pages for $(STORE_REPO)"
