fetch('http://localhost:8080/')
  .then(resp => resp.json())       // parse the response as JSON
  .then(r => console.log(r))       // log the parsed JSON to the console
  .catch(err => console.error(err)); // handle errors
