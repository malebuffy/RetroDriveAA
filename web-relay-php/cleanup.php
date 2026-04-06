<?php

declare(strict_types=1);
require_once __DIR__ . '/common.php';

relay_cleanup_expired_sessions();

echo "OK\n";
