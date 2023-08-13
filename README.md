# hotsauce-devilla

I recently attended an [excellent talk](https://www.meetup.com/tampa-jug/events/295033023/)
by [Joey deVilla](https://www.globalnerdy.com/) 
about building a Spring-boot-based web-app authenticated with JWT tokens issued by [auth0](https://auth0.com/).
He has written a [detailed article](https://auth0.com/blog/build-and-secure-an-api-with-spring-boot/) describing the process.

I realized I didn't really understand how the authentication worked, so I decided
to reimplement his "HotSauce" API in Scala, and implement the authentication myself.
This project is the result.

It's a portable [scala-cli](https://scala-cli.virtuslab.org/) project.
To run it, clone the repository, and in the top level directory type:

```zsh
% scala-cli run . -- https://<your-auth0-domain>.auth0.com/pem
```

The application requires as its single command-line argument
the URL of a certificate in pem format, from which an RSA public
key can be read to decode the `RS256`-signed [JWT tokens](https://jwt.io/)
that will be the application's authentication credentials.

(`auth0` provides a domain for each "tenant", from which among other blessings
you can download the certificate with the public key they use to
sign the JWT tokens they will issue for your application. See [below](#auth0-prerequisites)
for information on getting started with `auth0` if you'd like to try this app.)

Once you run the server, you can hit the unauthenticated
API immediately:

```zsh
% curl -i 'http://localhost:8080/api/hotsauces/?desc=All'
HTTP/1.1 200 OK
content-length: 558
Content-Type: application/json

[{"id":5,"brandName":"Hot Ones","sauceName":"The Last Dab","description":"More than simple mouth burn, Pepper X singes your soul. Starting with a pleasant burn in the mouth, the heat passes quickly, lulling you into a false confidence. You take another bite, enjoying the mustard and spice flavours. This would be great on jerk chicken, or Indian food! But then, WHAM! All of a sudden your skin goes cold and your stomach goes hot, and you realize the power of X.","url":"https://www.saucemania.com.au/hot-ones-the-last-dab-hot-sauce-148ml/","heat":1000000}]
```

But if you hit the authenticated API, well...

```zsh
% curl -i -X POST 'http://localhost:8080/api/hotsauces/1000' -d '{"brandName":"Really Tasty","sauceName":"Ouch!","description":"This one will hurt your mouth.","url":"https://dev.null/","heat":9000000}' 
HTTP/1.1 401 Unauthorized
content-length: 49
WWW-Authenticate: Bearer
Content-Type: text/plain; charset=UTF-8

Invalid value for: header Authorization (missing)
```

### auth0 prerequisites

To try the authenticated endpoints, you will need a JWT token, issued and signed by
whoever owns the public key in the cert to which you've provided a URL.
Probably that will be `auth0`.

[deVilla's article](https://auth0.com/blog/build-and-secure-an-api-with-spring-boot/) provides a detailed account of 
[how to get authentication set up at `auth0`](https://auth0.com/blog/build-and-secure-an-api-with-spring-boot/#Setting-Up-API-Authentication-on-the-Auth0-Side),
and then [how to get credentials](https://auth0.com/blog/build-and-secure-an-api-with-spring-boot/#Trying-Out-the-Secured-API) 
(a very long bearer token, encoded JWT) that the application will verify and accept.

In a nutshell, you 

1. [Make a free account](https://auth0.com/signup) on `auth0`
2. Create a new API using the default `RS256` signing algorithm
3. Retain the URL-ish `Identifier` you give it
4. Look up (in the "Machine to Machine Applications" tab) and retain
   - Your `Domain`
   - Your `Client ID`
   - Your `Client Secret`

Once you have all of that stuff, it is just...

```zsh
% curl --request POST --url https://<your-auth0-domain>.auth0.com/oauth/token --header 'content-type: application/json' --data '{"client_id": "<your-client-id>","client_secret": "<your-client-secret>","audience": "<your-URL-ish-identifier>","grant_type": "client_credentials"}'
```

You'll get back something like...

```
{"access_token":"<very-very-long-gibberish-token>","expires_in":86400,"token_type":"Bearer"}
```

Now we can hit the authenticated API:

```zsh
% curl -i -X POST 'http://localhost:8080/api/hotsauces/1000' -d '{"brandName":"Really Tasty","sauceName":"Ouch!","description":"This one will hurt your mouth.","url":"https://dev.null/","heat":9000000}' -H "authorization: Bearer <very-very-long-gibberish-token>"
HTTP/1.1 200 OK
content-length: 146
Content-Type: application/json

{"id":1000,"brandName":"Really Tasty","sauceName":"Ouch!","description":"This one will hurt your mouth.","url":"https://dev.null/","heat":9000000}
```

With each authenticated request, the server will print something like
```
Decoded JWT: header={alg=RS256, typ=JWT, kid=M8YYbGPBjl7YNzuzm1Dnc},body={iss=https://<your-auth0-domain>.auth0.com/, sub=ojokl5P7EkyPBN2Vu7qcdqaIYDLDDtwm@clients, aud=https://hotsauces-devilla.example.mchange.com/, iat=1691883039, exp=1691969439, azp=ojokl5P7EkyPBN2Vu7qcdqaIYDLDDtwm, gty=client-credentials},signature=dYkYOZzPv77zZDpqwhCmuxio_oZWIVA9bydr5yCwqYcRrCdJRZW_bNzgHufI4LLM-fnVJsQP9pMl34yZGm4jDRzd9c8sEgeKaSozKL1HYW-g70epFAfGx0MG-STPVKMour4fE6ZMm3RkpApcxUrd4TL-lYRm5gDKZMX6XW0cgQSMJlM-PT5wuhkDiS-zqLFIkKhZplTjjbbxjjXxxbfF17EPBqi_og2X5T3FNpugejnfQH9EZiAZT4CXPea14NtaE2c3aZY0ivQPYn2bkoaV5WWwjGECsYP_e_HkA1rI994xv-ZXjbCNF7-4jRmOON1bUv_Nz0LB8X4mzKJDnYzD-g
```

### Credits

This is just a reimplementation of Joey deVilla's project.
All the data in the mock database is lifted from that project as well!
Thank you Joey!

### Elsewhere

You can find a write up of this project here:
[Building an authenticated web service in Scala with tapir and JWT](https://tech.interfluidity.com/2023/08/13/building-an-authenticated-web-service-in-scala-with-tapir-and-jwt/index.html).