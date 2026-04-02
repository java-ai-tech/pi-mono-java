// MongoDB migration script: Add namespace field to existing sessions
// Usage: mongosh <connection-string> --eval 'var DB_NAME="delphi-agent-framework"' < migration-add-namespace.js

var databaseName = typeof DB_NAME !== 'undefined' && DB_NAME ? DB_NAME : 'delphi-agent-framework';
db = db.getSiblingDB(databaseName);

print('Starting migration: Add namespace field to agent_sessions in DB=' + databaseName);

// Backfill existing sessions with namespace='default'
const result = db.agent_sessions.updateMany(
    { namespace: { $exists: false } },
    { $set: { namespace: 'default' } }
);

print(`Updated ${result.modifiedCount} sessions with namespace='default'`);

// Create new indexes
print('Creating indexes...');

db.agent_sessions.createIndex(
    { namespace: 1, projectKey: 1, updatedAt: -1 },
    { name: 'namespace_project_updated_idx' }
);

db.agent_sessions.createIndex(
    { namespace: 1, id: 1 },
    { name: 'namespace_id_idx' }
);

print('Migration completed successfully');
