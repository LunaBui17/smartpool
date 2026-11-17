FROM php:8.3-apache
RUN docker-php-ext-install mysqli \
    && printf 'ServerName localhost\n' > /etc/apache2/conf-available/servername.conf \
    && a2enmod proxy proxy_http \
    && a2enconf servername
COPY deploy/raspberry-pi/apache-smartpool.conf /etc/apache2/conf-available/smartpool.conf
RUN a2enconf smartpool
COPY deploy/raspberry-pi/php.ini /usr/local/etc/php/conf.d/smartpool.ini
COPY backend/database.php backend/api_core.php backend/api_actions.php backend/api_data.php backend/pool_api.php /var/www/html/pool_api/
COPY backend/database.php backend/api_core.php backend/migration_lib.php backend/create_admin.php backend/migrate.php backend/reset_database.php /opt/smartpool/
COPY backend/migrations/ /opt/smartpool/migrations/
