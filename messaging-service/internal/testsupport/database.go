//go:build integration

package testsupport

import (
	"context"
	"database/sql"
	"github.com/google/uuid"
	"net/url"
	"os"
	"stockping.dev/messaging-service/internal/store"
	"testing"
	"time"
)

func Database(t *testing.T) *store.Postgres {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Fatal("TEST_DATABASE_URL is required for PostgreSQL integration tests")
	}
	admin, err := sql.Open("postgres", dsn)
	if err != nil {
		t.Fatal("open test database")
	}
	t.Cleanup(func() { _ = admin.Close() })
	schema := "test_" + uuid.New().String()
	schema = "\"" + schema + "\""
	if _, err = admin.Exec("CREATE SCHEMA " + schema); err != nil {
		t.Fatal("create test schema")
	}
	t.Cleanup(func() { _, _ = admin.Exec("DROP SCHEMA " + schema + " CASCADE") })
	u, err := url.Parse(dsn)
	if err != nil {
		t.Fatal("invalid database URL")
	}
	q := u.Query()
	q.Set("search_path", schema)
	u.RawQuery = q.Encode()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	db, err := store.Open(ctx, u.String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.DB.Close() })
	return db
}
