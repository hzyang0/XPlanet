ARG BASE_REGISTRY=docker.io/library
FROM ${BASE_REGISTRY}/python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app

COPY xplanet-agent/pyproject.toml ./pyproject.toml
COPY xplanet-agent/src ./src
RUN pip install --no-cache-dir .

EXPOSE 8000
CMD ["uvicorn", "xplanet_agent.api:app", "--host", "0.0.0.0", "--port", "8000"]
