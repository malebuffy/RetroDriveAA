<?php

declare(strict_types=1);
require_once __DIR__ . '/common.php';

relay_cleanup_expired_sessions();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    relay_json_response(405, ['error' => 'Method not allowed']);
}

relay_require_valid_init_key();

$transferId = relay_get_transfer_id();
$token = relay_get_token();

relay_with_lock($transferId, function () use ($transferId, $token): void {
    $meta = relay_load_meta($transferId);

    if (!empty($meta) && ($meta['token'] ?? '') !== $token) {
        relay_json_response(403, ['error' => 'Token mismatch']);
    }

    $status = (string)($meta['status'] ?? 'waiting');
    if ($status === 'ready' || $status === 'downloading' || $status === 'consumed') {
        relay_json_response(409, ['error' => 'Transfer already used', 'status' => $status]);
    }

    $createdAt = isset($meta['created_at']) ? (int)$meta['created_at'] : time();
    $meta = [
        'transfer_id' => $transferId,
        'token' => $token,
        'status' => 'waiting',
        'created_at' => $createdAt,
        'updated_at' => time(),
    ];

    relay_save_meta($transferId, $meta);
});

relay_json_response(200, ['ok' => true, 'status' => 'waiting']);
