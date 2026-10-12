"""Application configuration loaded from environment and the local .env file."""

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    mysql_root_password: str = ""
    mysql_database: str = ""
    mysql_user: str = ""
    mysql_password: str = ""

    database_url: str = Field(
        default="",
        validation_alias=AliasChoices("DATABASE_URL", "database_url"),
    )
    redis_url: str = "redis://127.0.0.1:6379/0"
    redis_host: str = "127.0.0.1"
    redis_port: int = 6379
    redis_db: int = 0

    rabbitmq_host: str = "127.0.0.1"
    rabbitmq_port: int = 5672
    rabbitmq_user: str = "railflow"
    rabbitmq_password: str = ""

    # Keep previous code-compatible attribute name. Never place actual secret in git.
    RAILFLOW_PAYMENT_CALLBACK_SECRET: str = ""


settings = Settings()
