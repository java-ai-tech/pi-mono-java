// MongoDB rollback script: Remove namespace field and indexes
// Usage: mongosh <connection-string> --eval 'var DB_NAME="delphi-agent-framework"' < rollback-add-namespace.js

var databaseName = typeof DB_NAME !== 'undefined' && DB_NAME ? DB_NAME : 'delphi-agent-framework';
db = db.getSiblingDB(databaseName);

print('Starting rollback: Remove namespace field and indexes in DB=' + databaseName);

// Drop new indexes
print('Dropping indexes...');
db.agent_sessions.dropIndex('namespace_project_updated_idx');
db.agent_sessions.dropIndex('namespace_id_idx');

// Remove namespace field
const result = db.agent_sessions.updateMany(
    { namespace: { $exists: true } },
    { $unset: { namespace: '', ownerRef: '' } }
);

print(`Removed namespace from ${result.modifiedCount} sessions`);
print('Rollback completed successfully');
