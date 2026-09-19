# Run from the server/ folder.
GRADLE := ../gradlew -p ..

.PHONY: dev test up down logs backup

# Zero-setup local server on http://localhost:8080 with a SQLite file (no Docker, no Postgres).
# Email verification is off in dev; the production default is on (TSUMUGI_REQUIRE_EMAIL_VERIFICATION).
dev:
	TSUMUGI_DB=sqlite:./dev.db TSUMUGI_REQUIRE_EMAIL_VERIFICATION=false TSUMUGI_JWT_SECRET=dev-only-secret-dev-only-secret-dev-only $(GRADLE) :server:run

test:
	$(GRADLE) :server:test

up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f server

# Writes a compressed Postgres dump to ./backups/.
backup:
	mkdir -p backups
	docker compose exec -T postgres pg_dump -U tsumugi -d tsumugi | gzip > backups/tsumugi-$$(date +%Y%m%d-%H%M%S).sql.gz
