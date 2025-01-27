Contains small POC project that iterates through trademarks api and tries detecting what appears to be duplicates.
The process stops after detecting 100 duplicates or when reaching 500*50 documents.

docker build . -t trademarks-api-iteration
docker run -e API_KEY=<api key> trademarks-api-iteration

Verbose output that pushes current and previously seen json documents to output
docker run -e API_KEY=<api key> -e VERBOSE=1 trademarks-api-iteration

