"""Create database tables that do not yet exist.

Run: python -m scripts.create_tables
"""

from app.db.init_db import main


if __name__ == "__main__":
    main()
