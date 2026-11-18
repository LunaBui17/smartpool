FROM php:8.3-apache
RUN docker-php-ext-install mysqli \
    && printf 'ServerName localhost\n' > /etc/apache2/conf-available/servername.conf \
    && a2enmod proxy proxy_http \
    && a2enconf servername
COPY --chmod=0644 deploy/raspberry-pi-v2/apache-smartpool.conf /etc/apache2/conf-available/smartpool.conf
RUN a2enconf smartpool
COPY --chmod=0644 deploy/raspberry-pi-v2/php.ini /usr/local/etc/php/conf.d/smartpool.ini
COPY --chmod=0644 backend/database.php backend/api_core.php backend/api_actions.php backend/api_data.php backend/api_runtime.php backend/api_hardware.php backend/api_device_delete.php backend/api_deprovision.php backend/pool_api.php /var/www/html/pool_api/
COPY backend/database.php backend/api_core.php backend/api_runtime.php backend/api_hardware.php backend/api_deprovision.php backend/api_device_delete.php backend/device_lifecycle.php backend/migration_lib.php backend/create_admin.php backend/migrate.php backend/reset_database.php backend/reset_role_passwords.php /opt/smartpool/
COPY backend/migrations/ /opt/smartpool/migrations/
RUN chmod 0755 /var/www/html/pool_api \
    && chmod 0644 /var/www/html/pool_api/*.php
