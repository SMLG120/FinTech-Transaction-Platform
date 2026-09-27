# Customer web UI: static files on nginx. No build step, no bundler, no secrets.
#
# There is deliberately no `npm run build` here. The app is dependency-free HTML/CSS/vanilla JS,
# so the image copies files rather than compiling them — which means the bytes served are the
# bytes reviewed, and a compromised build cache cannot inject anything into them.
FROM nginx:1.29.3-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY app/ /usr/share/nginx/html/

EXPOSE 80
