FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew build --no-daemon -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/distributions/LibereKollab-1.0-SNAPSHOT.tar .
RUN tar -xf LibereKollab-1.0-SNAPSHOT.tar --strip-components=1 && rm *.tar
ENV LIBREOFFICE_HOST=libreoffice
ENV LIBREOFFICE_PORT=2002
EXPOSE 8080
CMD ["bin/LibereKollab"]
