FROM amazoncorretto:21-al2023

RUN yum update -y && \
    yum install -y bash curl git maven tar gzip which procps --skip-broken && \
    yum clean all
