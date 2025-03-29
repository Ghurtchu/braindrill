#!/bin/bash

for i in {1..50}
do
  curl -X POST localhost:8080/lang/python -d "
while True:
  print('yes')
  " &
done

wait