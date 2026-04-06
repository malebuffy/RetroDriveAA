<?php

declare(strict_types=1);
require_once __DIR__ . '/common.php';

relay_cleanup_expired_sessions();

$transferId = relay_get_transfer_id();
$token = relay_get_token();

$meta = relay_with_lock($transferId, function () use ($transferId): array {
    return relay_load_meta($transferId);
});

if (empty($meta)) {
    relay_json_response(200, [
        'status' => 'waiting',
        'transfer_id' => $transferId,
    ]);
}

if (($meta['token'] ?? '') !== $token) {
    relay_json_response(403, ['error' => 'Token mismatch']);
}

relay_json_response(200, [
    'status' => (string)($meta['status'] ?? 'waiting'),
    'transfer_id' => $transferId,
    'filename' => (string)($meta['filename'] ?? ''),
    'size' => (int)($meta['size'] ?? 0),
    'updated_at' => (int)($meta['updated_at'] ?? 0),
]);
