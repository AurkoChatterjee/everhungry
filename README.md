# EverHungry

A multi-vendor food delivery platform. Customers order from independent restaurants, restaurant owners run their own kitchen dashboards, and delivery drivers claim and deliver orders. Order status changes and driver locations reach the browser in real time.

Built with Java 17, Maven, plain JDBC and MySQL. The frontend is plain HTML, CSS and JavaScript in a glassmorphism style.

## Run it (Windows, IntelliJ)

1. Start MySQL (MySQL Workbench should show your server as running).
2. Open `src/main/resources/application.properties` and set `db.user` and `db.password` to your MySQL login.
3. In IntelliJ choose **File > Open** and select the `pom.xml`. Let Maven import finish.
4. Open `src/main/java/com/everhungry/Main.java` and click the green run arrow next to `main`.
5. Open http://localhost:8080

The `everhungry` database, its tables and the demo data are created automatically on first start.

### From a terminal

```
mvn compile exec:java -Dexec.mainClass=com.everhungry.Main
```

or build a single runnable jar:

```
mvn package
java -jar target\everhungry.jar
```

Settings can also come from environment variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `PORT`, `DELIVERY_FEE`. In PowerShell: `$env:DB_PASSWORD="yourpassword"`.

### Tests

```
mvn test
```

## Demo accounts

Password for every account: `demo123`

| Role | Email |
|---|---|
| Customer | customer@everhungry.demo |
| Restaurant owner | owner1@everhungry.demo, owner2@everhungry.demo, owner3@everhungry.demo |
| Driver | driver1@everhungry.demo, driver2@everhungry.demo |
| Admin | admin@everhungry.demo |

## Admin

The admin account is created automatically on startup if none exists, so it also works on a database you already have. Change its password before sharing the app with anyone.

The admin page has four tabs: an overview (order totals, delivered revenue, orders over the last 7 days, orders by status), every order across all restaurants, a list of restaurants where any of them can be closed or reopened, and a list of users where accounts can be suspended or restored. A suspended account can't log in and its current session stops working. Admin accounts can't be created from the sign-up form.

## Try the full order flow

Open three browser windows (use a normal window plus two private windows so the sessions stay separate).

1. **Customer:** log in, open a restaurant, add dishes, pin the drop-off point on the map, place the order.
2. **Owner (owner1 for Spice Route Kitchen):** on the Kitchen page the order appears under New orders. Accept it, start preparing, then mark it ready.
3. **Driver:** the order shows up under Waiting for a driver as soon as it is accepted. Take it. Click **Simulate a ride** to move along the route, or **Share my live location** to use real GPS.
4. When the order is ready, mark it picked up, start the trip, then mark it delivered.

The customer's My orders page updates at every step, and the driver's marker moves on the map during the trip.

## Order statuses

`PLACED` > `ACCEPTED` > `PREPARING` > `READY` > `PICKED_UP` > `ON_THE_WAY` > `DELIVERED`

| Change | Who can make it |
|---|---|
| PLACED to ACCEPTED or REJECTED | Restaurant owner |
| PLACED to CANCELLED | Customer |
| ACCEPTED to PREPARING, PREPARING to READY | Restaurant owner |
| READY to PICKED_UP, PICKED_UP to ON_THE_WAY, ON_THE_WAY to DELIVERED | The assigned driver |

A driver can claim an order once it is accepted. The claim is atomic, so two drivers cannot take the same order, and a driver can hold at most three active orders.

## Project layout

```
src/main/java/com/everhungry
  Main.java          starts the HTTP server
  Api.java           all /api routes
  OrderFlow.java     status transitions and who may make them
  Events.java        server-sent events to customers, owners and drivers
  Auth.java          PBKDF2 password hashing, cookie sessions
  Schema.java        tables and demo data
  Db.java, Config.java, Json.java, Ctx.java, StaticHandler.java
src/main/resources
  application.properties
  web/               HTML pages, css/app.css, js/app.js
src/test/java        JUnit 5 tests for the order state machine
```

Maps use Leaflet with OpenStreetMap data through CARTO tiles, so the browser needs internet access for map tiles and fonts.

## Creator

A. Chatterjee, https://github.com/AurkoChatterjee (see the Creator page in the app).
