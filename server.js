const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const cors = require('cors');
const fs = require('fs');
const path = require('path');

const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const DB_FILE = path.join(__dirname, 'usuarios_db.json');

// Base de datos inicial con 2 cuentas reales para que puedas probar de inmediato
const datosIniciales = {
    usuarios: [
        {
            cuenta: "2200112233",
            usuario: "marco",
            pin: "1234",
            nombre: "Marco Cabrera",
            saldo: 1250.00
        },
        {
            cuenta: "2200998877",
            usuario: "carlos",
            pin: "1234",
            nombre: "Carlos Andrade (Docente)",
            saldo: 800.00
        }
    ]
};

function leerDB() {
    if (!fs.existsSync(DB_FILE)) {
        fs.writeFileSync(DB_FILE, JSON.stringify(datosIniciales, null, 2));
    }
    return JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
}

function guardarDB(data) {
    fs.writeFileSync(DB_FILE, JSON.stringify(data, null, 2));
}

// Mapa de conexiones WebSocket por número de cuenta
const clientesConectados = new Map();

wss.on('connection', (ws) => {
    let cuentaAsociada = null;

    ws.on('message', ( msg ) => {
        try {
            const data = JSON.parse(msg);
            if (data.tipo === 'SUSCRIBIR' && data.cuenta) {
                cuentaAsociada = data.cuenta;
                clientesConectados.set(cuentaAsociada, ws);
                console.log(`📱 [WS] Cuenta ${cuentaAsociada} en línea`);
            }
        } catch (e) {}
    });

    ws.on('close', () => {
        if (cuentaAsociada) {
            clientesConectados.delete(cuentaAsociada);
            console.log(`📴 [WS] Cuenta ${cuentaAsociada} desconectada`);
        }
    });
});

function notificarCuentaEnVivo(cuentaDestino, payload) {
    const msg = JSON.stringify(payload);
    // Enviar al dueño de la cuenta y a los clientes activos
    wss.clients.forEach(client => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(msg);
        }
    });
}

// 1. REGISTRO DE NUEVO USUARIO
app.post('/api/registro', (req, res) => {
    const { nombre, usuario, pin, saldoInicial } = req.body;
    if (!nombre || !usuario || !pin) {
        return res.status(400).json({ error: 'Completa todos los campos' });
    }

    const db = leerDB();
    if (db.usuarios.find(u => u.usuario.toLowerCase() === usuario.toLowerCase())) {
        return res.status(400).json({ error: 'Ese nombre de usuario ya existe' });
    }

    // Generar número de cuenta bancario único de 10 dígitos (empieza con 2200)
    const nuevaCuenta = "2200" + Math.floor(100000 + Math.random() * 900000).toString();
    const nuevoUsuario = {
        cuenta: nuevaCuenta,
        usuario: usuario.trim(),
        pin: pin.trim(),
        nombre: nombre.trim(),
        saldo: parseFloat(saldoInicial) || 500.00
    };

    db.usuarios.push(nuevoUsuario);
    guardarDB(db);

    console.log(`✅ Nueva cuenta creada: ${nuevoUsuario.nombre} -> Nro: ${nuevaCuenta}`);
    res.json({ status: 'OK', usuario: nuevoUsuario });
});

// 2. LOGIN DE USUARIO (Por usuario o por número de cuenta)
app.post('/api/login', (req, res) => {
    const { identificador, pin } = req.body;
    const db = leerDB();

    const user = db.usuarios.find(
        u => (u.usuario.toLowerCase() === (identificador || '').toLowerCase() || u.cuenta === identificador) && u.pin === pin
    );

    if (!user) {
        return res.status(401).json({ error: 'Usuario/Cuenta o PIN incorrectos' });
    }

    res.json({ status: 'OK', usuario: user });
});

// 3. CONSULTAR SI UNA CUENTA EXISTE (Para validar antes de transferir)
app.get('/api/cuenta/:numero', (req, res) => {
    const db = leerDB();
    const user = db.usuarios.find(u => u.cuenta === req.params.numero);
    if (!user) {
        return res.status(404).json({ existe: false, error: 'La cuenta ingresada no existe' });
    }
    res.json({ existe: true, cuenta: user.cuenta, nombre: user.nombre });
});

// 4. LISTAR DIRECTORIO DE CUENTAS EXISTENTES (Para ver fácilmente a quién transferir)
app.get('/api/cuentas', (req, res) => {
    const db = leerDB();
    const lista = db.usuarios.map(u => ({ cuenta: u.cuenta, nombre: u.nombre, usuario: u.usuario }));
    res.json(lista);
});

// 5. DEPÓSITO (REST)
app.post('/api/deposito', (req, res) => {
    const { cuenta, monto, comentario } = req.body;
    const valor = parseFloat(monto);
    if (!valor || valor <= 0) return res.status(400).json({ error: 'Monto inválido' });

    const db = leerDB();
    const user = db.usuarios.find(u => u.cuenta === cuenta);
    if (!user) return res.status(404).json({ error: 'Cuenta no encontrada' });

    user.saldo += valor;
    guardarDB(db);

    res.json({
        status: 'OK',
        saldo: user.saldo,
        mensaje: 'Depósito acreditado',
        detalle: comentario || 'Recarga en línea'
    });
});

// 6. RETIRO (REST)
app.post('/api/retiro', (req, res) => {
    const { cuenta, monto, comentario } = req.body;
    const valor = parseFloat(monto);
    if (!valor || valor <= 0) return res.status(400).json({ error: 'Monto inválido' });

    const db = leerDB();
    const user = db.usuarios.find(u => u.cuenta === cuenta);
    if (!user) return res.status(404).json({ error: 'Cuenta no encontrada' });
    if (valor > user.saldo) return res.status(400).json({ error: 'Fondos insuficientes en tu cuenta' });

    user.saldo -= valor;
    guardarDB(db);

    res.json({
        status: 'OK',
        saldo: user.saldo,
        mensaje: 'Retiro exitoso',
        detalle: comentario || 'Retiro sin tarjeta'
    });
});

// 7. PAGO DE SERVICIOS BÁSICOS (REST)
app.post('/api/servicios', (req, res) => {
    const { cuenta, monto, contrato, comentario } = req.body;
    const valor = parseFloat(monto);
    if (!valor || valor <= 0) return res.status(400).json({ error: 'Monto inválido' });

    const db = leerDB();
    const user = db.usuarios.find(u => u.cuenta === cuenta);
    if (!user) return res.status(404).json({ error: 'Cuenta no encontrada' });
    if (valor > user.saldo) return res.status(400).json({ error: 'Fondos insuficientes para pagar el servicio' });

    user.saldo -= valor;
    guardarDB(db);

    res.json({
        status: 'OK',
        saldo: user.saldo,
        mensaje: 'Servicio Básico Pagado',
        detalle: `Contrato: ${contrato || 'S/N'} ${comentario ? '• ' + comentario : ''}`
    });
});

// 8. TRANSFERENCIA P2P CON VALIDACIÓN ESTRICTA DE CUENTA + COMENTARIOS + WEBSOCKET
app.post('/api/transferir', (req, res) => {
    const { cuentaOrigen, cuentaDestino, monto, comentario, nombreEmisorExterno } = req.body;
    const valor = parseFloat(monto);

    if (!valor || valor <= 0) return res.status(400).json({ error: 'El monto debe ser mayor a $0.00' });
    if (!cuentaDestino) return res.status(400).json({ error: 'Debes indicar la cuentaDestino' });

    const db = leerDB();
    const receptor = db.usuarios.find(u => u.cuenta === cuentaDestino);

    // VALIDACIÓN ESTRICTA: Si la cuenta destino NO existe, rechazar inmediatamente
    if (!receptor) {
        return res.status(404).json({
            error: `La cuenta destino Nro. ${cuentaDestino} NO existe en el banco. Verifique el número.`
        });
    }

    let emisorNombre = nombreEmisorExterno || "Transferencia Externa (Postman)";
    let emisorCuenta = cuentaOrigen || "EXTERNO-POSTMAN";
    let saldoEmisorActualizado = null;

    // Si viene desde una cuenta registrada en la App (cuentaOrigen)
    if (cuentaOrigen) {
        if (cuentaOrigen === cuentaDestino) {
            return res.status(400).json({ error: 'No puedes transferir dinero a tu propia cuenta' });
        }
        const emisor = db.usuarios.find(u => u.cuenta === cuentaOrigen);
        if (!emisor) {
            return res.status(404).json({ error: 'La cuenta de origen no existe' });
        }
        if (valor > emisor.saldo) {
            return res.status(400).json({ error: 'Fondos insuficientes para realizar la transferencia' });
        }

        emisor.saldo -= valor;
        emisorNombre = emisor.nombre;
        emisorCuenta = emisor.cuenta;
        saldoEmisorActualizado = emisor.saldo;
    }

    // Sumar saldo al receptor
    receptor.saldo += valor;
    guardarDB(db);

    const motivoTxt = comentario ? `"${comentario}"` : 'Sin comentario';
    const horaActual = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });

    console.log(`💸 [P2P] De: ${emisorNombre} (${emisorCuenta}) -> Para: ${receptor.nombre} (${receptor.cuenta}) | $${valor.toFixed(2)} | ${motivoTxt}`);

    // Notificar en TIEMPO REAL por WebSocket al celular que tiene esa cuenta abierta
    notificarCuentaEnVivo(receptor.cuenta, {
        tipo: 'TRANSFERENCIA_RECIBIDA',
        cuentaDestino: receptor.cuenta,
        nombreReceptor: receptor.nombre,
        nombreEmisor: emisorNombre,
        cuentaEmisor: emisorCuenta,
        monto: valor,
        saldoNuevo: receptor.saldo,
        comentario: comentario || 'Transferencia directa',
        detalleCompleto: `De: ${emisorNombre} (Cta. ${emisorCuenta}) • Motivo: ${comentario || 'Transferencia P2P'}`,
        fecha: horaActual
    });

    res.json({
        status: 'OK',
        saldo: saldoEmisorActualizado !== null ? saldoEmisorActualizado : receptor.saldo,
        destinatario: {
            nombre: receptor.nombre,
            cuenta: receptor.cuenta
        },
        comentario: comentario || 'Transferencia P2P',
        mensaje: `Transferencia exitosa a ${receptor.nombre} (Cta. ${receptor.cuenta})`
    });
});

const PORT = 3000;
server.listen(PORT, '0.0.0.0', () => {
    console.log('===========================================================');
    console.log(`🏦 BANCA DISTRIBUIDA ACTIVA EN PUERTO ${PORT}`);
    console.log(`📋 Cuentas pre-creadas para pruebas:`);
    console.log(`   1) Cuenta: 2200112233 | Usuario: marco  | PIN: 1234`);
    console.log(`   2) Cuenta: 2200998877 | Usuario: carlos | PIN: 1234`);
    console.log('===========================================================');
});