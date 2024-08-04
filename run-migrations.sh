#docker run --rm \
#  -v $(pwd)/src/main/resources/db/migration:/flyway/sql \
#  -v $(pwd)/src/main/resources:/flyway/conf \
#  flyway/flyway migrate

  docker run --rm \
    -v "$(pwd)/src/main/resources/db/migration:/flyway/sql" \
    -v "$(pwd)/src/main/resources:/flyway/conf" \
    flyway/flyway migrate

