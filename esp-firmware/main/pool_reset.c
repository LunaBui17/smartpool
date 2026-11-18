#include "pool_reset.h"
#include "esp_mac.h"
#include "nvs.h"
#include <stdio.h>
#include <stdbool.h>
#include <string.h>

static esp_err_t validate(const pool_reset_job_t *job)
{
    if(job->version!=1||!memchr(job->job_id,0,sizeof(job->job_id))||strlen(job->job_id)!=36||
       !memchr(job->token,0,sizeof(job->token))||strlen(job->token)!=64||
       !memchr(job->api_url,0,sizeof(job->api_url))||!job->api_url[0]||
       !memchr(job->device_id,0,sizeof(job->device_id))||!job->device_id[0]||
       !memchr(job->factory_mac,0,sizeof(job->factory_mac)))return ESP_ERR_INVALID_ARG;
    for(int i=0;i<64;i++)if(!((job->token[i]>='0'&&job->token[i]<='9')||(job->token[i]>='a'&&job->token[i]<='f')))return ESP_ERR_INVALID_ARG;
    for(int i=0;i<36;i++) {
        char c=job->job_id[i];bool dash=i==8||i==13||i==18||i==23;
        if(dash?c!='-':!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return ESP_ERR_INVALID_ARG;
    }
    if(strncmp(job->api_url,"http://",7)&&strncmp(job->api_url,"https://",8))return ESP_ERR_INVALID_ARG;
    uint8_t mac[6];char text[18];
    esp_err_t error=esp_efuse_mac_get_default(mac);if(error!=ESP_OK)return error;
    snprintf(text,sizeof(text),"%02X:%02X:%02X:%02X:%02X:%02X",mac[0],mac[1],mac[2],mac[3],mac[4],mac[5]);
    return strcmp(text,job->factory_mac)?ESP_ERR_INVALID_STATE:ESP_OK;
}
esp_err_t pool_reset_load(pool_reset_job_t *job)
{
    nvs_handle_t handle;esp_err_t error=nvs_open("poolreset",NVS_READONLY,&handle);
    if(error!=ESP_OK)return error;
    size_t size=sizeof(*job);error=nvs_get_blob(handle,"job",job,&size);nvs_close(handle);
    if(error==ESP_OK)error=size==sizeof(*job)?validate(job):ESP_ERR_INVALID_STATE;
    return error;
}
esp_err_t pool_reset_save(const pool_reset_job_t *job)
{
    esp_err_t error=validate(job);if(error!=ESP_OK)return error;
    nvs_handle_t handle;error=nvs_open("poolreset",NVS_READWRITE,&handle);if(error!=ESP_OK)return error;
    error=nvs_set_blob(handle,"job",job,sizeof(*job));if(error==ESP_OK)error=nvs_commit(handle);nvs_close(handle);return error;
}
static esp_err_t erase_namespace(const char *name)
{
    nvs_handle_t handle;esp_err_t error=nvs_open(name,NVS_READWRITE,&handle);if(error!=ESP_OK)return error;
    error=nvs_erase_all(handle);if(error==ESP_OK)error=nvs_commit(handle);nvs_close(handle);return error;
}
esp_err_t pool_reset_clear_app(void)
{
    const char *names[]={"poolnet","poolid","poolq"};
    for(int i=0;i<3;i++){esp_err_t error=erase_namespace(names[i]);if(error!=ESP_OK)return error;}
    return ESP_OK;
}
esp_err_t pool_reset_forget(void){return erase_namespace("poolreset");}
