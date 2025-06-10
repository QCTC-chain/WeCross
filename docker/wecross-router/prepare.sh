#!/bin/bash

# set -e
# LANG=en_US.UTF-8

ROOT=$(pwd)

cd ../..
./gradlew build -x test

cd ${ROOT}
mkdir -p wecross-router
cp -r ../../dist/* wecross-router/

cat >>${ROOT}/wecross-router/start.sh <<EOF
while true; do
  sleep 1
  ttime=`date +"%Y-%m-%d %H:%M:%S"`
  echo $ttime
done
EOF

tar -zcvf wecross-router.tar.gz wecross-router

docker build -t registry.cn-beijing.aliyuncs.com/qctc-bmsp/wecross-router:v1.3.1-dev -m 4g .

rm -rf wecross-router