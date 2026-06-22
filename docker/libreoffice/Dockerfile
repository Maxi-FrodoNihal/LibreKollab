FROM debian:12-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        libreoffice-core \
        libreoffice-writer \
        libreoffice-java-common \
        default-jre-headless && \
    rm -rf /var/lib/apt/lists/* \
            /usr/lib/libreoffice/help \
            /usr/lib/libreoffice/share/gallery \
            /usr/lib/libreoffice/share/template

EXPOSE 2002

CMD ["soffice", "--headless", \
     "--accept=socket,host=0.0.0.0,port=2002;urp;StarOffice.ServiceManager", \
     "--norestore", "--nologo", "--nolockcheck"]
