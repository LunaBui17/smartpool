FROM python:3.11-slim
RUN pip install --no-cache-dir paho-mqtt==2.1.0 PyMySQL==1.1.1
WORKDIR /worker
COPY deploy/raspberry-pi-v2/control-worker/worker.py /worker/worker.py
CMD ["python", "-u", "/worker/worker.py"]
