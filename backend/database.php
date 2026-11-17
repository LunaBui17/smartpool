<?php
// Environment overrides allow the same backend to run on XAMPP or Raspberry Pi.
function pool_database_connect()
{
    $env = function ($name, $fallback) {
        $value = getenv($name);
        return $value === false ? $fallback : $value;
    };
    mysqli_report(MYSQLI_REPORT_OFF);
    return new mysqli(
        $env('POOL_DB_HOST', 'localhost'),
        $env('POOL_DB_USER', 'root'),
        $env('POOL_DB_PASSWORD', ''),
        $env('POOL_DB_NAME', 'pool_db'),
        (int)$env('POOL_DB_PORT', '3306')
    );
}
