
class api {
    /**
     * @param request
     * @returns {Promise<void>}
     */
    sendRequest = async function(request) {
        let h = new Headers();
        h.set("Accept", "application/json");
        h.set("Content-Type", "application/json");
        await fetch("/api/proxy.php", {
            headers:h,
            method: "POST",
            body: JSON.stringify(request)
        }).then(function (res ) {
            var reader = res.body.getReader();
            return new ReadableStream({
                start(controller) {
                    return pump();

                    function pump() {
                        return reader.read().then(({done, value}) => {
                            // When no more data needs to be consumed, close the stream
                            if (done) {
                                controller.close();
                                return;
                            }
                            // Enqueue the next data chunk into our target stream
                            controller.enqueue(value);
                            return pump();
                        });
                    }
                },
            });
        }).then((stream) => {
            return new Response(stream);
        }).then(resp => {
            resp.json().then((foo) => {
                return foo;
            })
        })
    }
}