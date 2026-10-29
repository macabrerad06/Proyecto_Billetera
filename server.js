const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const cors = require('cors');
const fs = require('fs');
const path = require('path');

const app = express();
app.use(cors());
app.use(express.json());

const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const DB_FILE = path.join(__dirname, 'usuarios_db.json');

// Base de datos 100% limpia (sin cuentas pre-creadas)
function leerDB() {
    if (!fs.existsSync(DB_FILE)) {
        fs.writeFileSync(DB_FILE, JSON.stringify({ usuarios: [] }, null, 2));
    }
    return JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
}

function guardarDB(data) {
    fs.writeFileSync(DB_FILE, JSON.stringify(data, null, 2));
}

wss.on('connection', (ws) => {
    ws.on('message', (msg) => {
        try {
            const data = JSON.parse(msg);
            if (data.tipo === 'SUSCRIBIR' && data.cuenta) {
                ws.cuentaAsociada = data.cuenta;
                console.log(`[WS] Celular conectado -> Cuenta: ${data.cuenta}`);
            }
        } catch (e) {}
    });
});

function notificarAppAndroid( payload ) {
    const msg = JSON.stringify(payload);
    wss.clients.forEach(client => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(msg);
        }
    });
}

// 1. REGISTRO DE NUEVA CUENTA DESDE LA APP ANDROID
app.post('/api/registro', (req, res) => {
    const { nombre, usuario, pin, saldoInicial } = req.body || {};
    if (!nombre || !usuario || !pin) {
        return res.status(400).json({ error: 'Completa todos los campos' });
    }

    const db = leerDB();
    if (db.usuarios.find(u => u.usuario.toLowerCase() === usuario.trim().toLowerCase())) {
        return res.status(400).json({ error: 'Ese usuario ya está registrado' });
    }

    const nuevaCuenta = "2200" + Math.floor(100000 + Math.random() * 900000).toString();
    const nuevoUsuario = {
        cuenta: nuevaCuenta,
        usuario: usuario.trim(),
        pin: pin.trim(),
        nombre: nombre.trim(),
        saldo: parseFloat(saldoInicial) || 0.00
    };

    db.usuarios.push(nuevoUsuario);
    guardarDB(db);

    console.log(`[REGISTRO] Titular: ${nuevoUsuario.nombre} | Cta: ${nuevaCuenta}`);
    res.json({ status: 'OK', usuario: nuevoUsuario });
});

// 2. LOGIN DESDE LA APP ANDROID
app.post('/api/login', (req, res) => {
    const { identificador, pin } = req.body || {};
    const db = leerDB();

    const user = db.usuarios.find(
        u => (u.usuario.toLowerCase() === (identificador || '').trim().toLowerCase() || u.cuenta === (identificador || '').trim()) && u.pin === (pin || '').trim()
    );

    if (!user) return res.status(401).json({ error: 'Credenciales incorrectas' });
    res.json({ status: 'OK', usuario: user });
});

// 3. VERIFICAR SI LA CUENTA EXISTE ANTES DE ENVIAR DINERO
app.get('/api/cuenta/:numero', (req, res) => {
    const db = leerDB();
    const user = db.usuarios.find(u => u.cuenta === req.params.numero);
    if (!user) return res.status(404).json({ existe: false, error: 'Cuenta no existe' });
    res.json({ existe: true, cuenta: user.cuenta, nombre: user.nombre });
});

// 4. LISTAR CUENTAS REGISTRADAS (Para poder ver en la app qué cuentas has creado)
app.get('/api/cuentas', (req, res) => {
    const db = leerDB();
    res.json(db.usuarios.map(u => ({ cuenta: u.cuenta, nombre: u.nombre, usuario: u.usuario })));
});

// 5. DEPÓSITO
app.post('/api/deposito', (req, res) => {
    const { cuenta, monto } = req.body || {};
    const valor = parseFloat(monto);
    const db = leerDB();
    const user = db.usuarios.find(u => u.cuenta === cuenta);
    if (!user || !valor || valor <= 0) return res.status(400).json({ error: 'Datos inválidos' });

    user.saldo += valor;
    guardarDB(db);
    res.json({ status: 'OK', saldo: user.saldo, mensaje: 'Depósito realizado' });
});

// 6. RETIRO
app.post('/api/retiro', (req, res) => {
    const { cuenta, monto } = req.body || {};
    const valor = parseFloat(monto);
    const db = leerDB();
    const user = db.usuarios.find(u => u.cuenta === cuenta);
    if (!user || !valor || valor <= 0) return res.status(400).json({ error: 'Datos inválidos' });
    if (valor > user.saldo) return res.status(400).json({ error: 'Fondos insuficientes' });

    user.saldo -= valor;
    guardarDB(db);
    res.json({ status: 'OK', saldo: user.saldo, mensaje: 'Retiro realizado' });
});

// 7. PAGO DE SERVICIOS BÁSICOS
app.post('/api/servicios', (req, res) => {
    const { cuenta, monto } = req.body || {};
    const valor = parseFloat(monto);
    const db = leerDB();
    const user = db.usuarios.find(u => u.cuenta === cuenta);
    if (!user || !valor || valor <= 0) return res.status(400).json({ error: 'Datos inválidos' });
    if (valor > user.saldo) return res.status(400).json({ error: 'Fondos insuficientes' });

    user.saldo -= valor;
    guardarDB(db);
    res.json({ status: 'OK', saldo: user.saldo, mensaje: 'Servicio pagado' });
});

// 8. TRANSFERENCIA P2P (Desde App Android o desde Postman)
app.post('/api/transferir', (req, res) => {
    const { cuentaOrigen, cuentaDestino, monto, comentario } = req.body || {};
    const valor = parseFloat(monto);

    if (!valor || valor <= 0) return res.status(400).json({ error: 'Monto inválido' });
    if (!cuentaDestino) return res.status(400).json({ error: 'Falta cuentaDestino' });

    const db = leerDB();
    const receptor = db.usuarios.find(u => u.cuenta === cuentaDestino);
    if (!receptor) return res.status(404).json({ error: 'La cuenta destino NO existe' });

    let nombreEmisor = "Transferencia Postman";
    let ctaEmisor = cuentaOrigen || "EXTERNO";
    let saldoRespuesta = receptor.saldo;

    if (cuentaOrigen) {
        if (cuentaOrigen === cuentaDestino) return res.status(400).json({ error: 'Misma cuenta' });
        const emisor = db.usuarios.find(u => u.cuenta === cuentaOrigen);
        if (!emisor) return res.status(404).json({ error: 'Cuenta origen no existe' });
        if (valor > emisor.saldo) return res.status(400).json({ error: 'Fondos insuficientes' });

        emisor.saldo -= valor;
        nombreEmisor = emisor.nombre;
        ctaEmisor = emisor.cuenta;
        saldoRespuesta = emisor.saldo;
    }

    receptor.saldo += valor;
    guardarDB(db);

    notificarAppAndroid({
        tipo: 'TRANSFERENCIA_RECIBIDA',
        cuentaDestino: receptor.cuenta,
        nombreEmisor: nombreEmisor,
        cuentaEmisor: ctaEmisor,
        monto: valor,
        saldoNuevo: receptor.saldo,
        comentario: comentario || 'Transferencia P2P'
    });

    res.json({
        status: 'OK',
        saldo: saldoRespuesta,
        destinatario: { nombre: receptor.nombre, cuenta: receptor.cuenta },
        comentario: comentario || 'Transferencia P2P',
        mensaje: `Transferencia enviada a ${receptor.nombre}`
    });
});

server.listen(3000, '0.0.0.0', () => {
    console.log('Servidor corriendo en puerto 3000');
});