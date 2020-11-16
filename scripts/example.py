import json, requests

#
# Loads additional targets into program "p-2".  Update "url" to point to the
# appropriate database.
#

#url = "http://localhost:8080/odb"
url = "https://lucuma-odb-staging.herokuapp.com/odb"
pid = "p-2"

targets = [
  {
    "name":  "Bellatrix",
    "ra":    { "hms": "05:25:07.863" },
    "dec":   { "dms": "06:20:58.93"  },
    "epoch": "J2000.000",
    "properMotion": {
      "ra":  { "milliarcsecondsPerYear": -8.11 },
      "dec": { "milliarcsecondsPerYear": -12.88  }
    },
    "radialVelocity": { "metersPerSecond": 18287 },
    "parallax":       { "milliarcseconds":  12.92 },
    "magnitudes": [
      {
        "band": "R",
        "value": 1.73,
        "system": "VEGA"
      },
      {
        "band": "V",
        "value": 1.64,
        "system": "VEGA"
      }
    ]
  },

  {
    "name":  "Alnilam",
    "ra":    { "hms": "05:36:12.813" },
    "dec":   { "dms": "-01:12:06.91"  },
    "epoch": "J2000.000",
    "properMotion": {
      "ra":  { "milliarcsecondsPerYear": 1.44 },
     "dec": { "milliarcsecondsPerYear": -0.78 }
    },
    "radialVelocity": { "metersPerSecond": 27280 },
    "parallax":       { "milliarcseconds": 1.65 },
    "magnitudes": [
      {
        "band": "R",
        "value": 1.76,
        "system": "VEGA"
      },
      {
        "band": "V",
        "value": 1.69,
        "system": "VEGA"
      }
    ]
  },

  {
    "name":  "Alnitak",
    "ra":    { "hms": "05:40:45.527" },
    "dec":   { "dms": "-01:56:33.26"  },
    "epoch": "J2000.000",
    "properMotion": {
      "ra":  { "milliarcsecondsPerYear": 3.19 },
     "dec": { "milliarcsecondsPerYear":  2.03 }
    },
    "radialVelocity": { "metersPerSecond": 18587 },
    "parallax":       { "milliarcseconds": 4.43 },
    "magnitudes": [
      {
        "band": "R",
        "value": 1.85,
        "system": "VEGA"
      },
      {
        "band": "V",
        "value": 1.77,
        "system": "VEGA"
      }
    ]
  },

  {
    "name":  "Saiph",
    "ra":    { "hms": "05:47:45.389" },
    "dec":   { "dms": "-09:40:10.58"  },
    "epoch": "J2000.000",
    "properMotion": {
      "ra":  { "milliarcsecondsPerYear": 1.46 },
     "dec": { "milliarcsecondsPerYear": -1.28 }
    },
    "radialVelocity": { "metersPerSecond": 20385 },
    "parallax":       { "milliarcseconds": 5.04 },
    "magnitudes": [
      {
        "band": "R",
        "value": 2.09,
        "system": "VEGA"
      },
      {
        "band": "V",
        "value": 2.06,
        "system": "VEGA"
      }
    ]
  },


]

# The GraphQL query.
query = """
mutation CreateSiderealTarget($createSidereal: CreateSiderealInput!) {
  createSiderealTarget(input: $createSidereal) {
    id
    name
    tracking {
      __typename
      ... on Sidereal {
        coordinates {
          ra  { hms }
          dec { dms }
        }
        epoch
        properMotion {
          ra { milliarcsecondsPerYear }
          dec { milliarcsecondsPerYear }
        }
        radialVelocity { kilometersPerSecond }
        parallax { microarcseconds }
      }
    }
    magnitudes {
      value
      band
      system
    }
  }
}
"""

# Execute the query for each target
for t in targets:
  # Add program id
  t["programIds"] = [ "p-2" ]

  # Wrap with input name
  variables = {
    "createSidereal": t
  }

  result = requests.post(url, json={'query': query, 'operationName': "CreateSiderealTarget", 'variables': variables})
  code   = result.status_code

  if code == 200:
    print(json.dumps(result.json()["data"]["createSiderealTarget"], indent=2))
  elif code >= 400 and code < 500:
    print(json.dumps(result.json()["errors"], indent=2))

  result.raise_for_status()
